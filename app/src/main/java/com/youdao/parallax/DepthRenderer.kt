package com.youdao.parallax

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.android.UiHelper
import com.google.android.filament.filamat.MaterialBuilder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * 渲染模式枚举
 */
enum class RenderMode {
    /** 单层渲染模式：性能优先，使用单个四边形和纹理坐标偏移 */
    SINGLE_LAYER,
    /** 多层渲染模式：质量优先，将深度图分割为多个层次，实现真实的深度遮挡 */
    MULTI_LAYER
}

/**
 * 基于深度图的视差效果渲染器
 *
 * 该类使用 Google Filament 渲染引擎实现基于深度图的 3D 视差效果。
 * 通过设备的旋转向量传感器检测倾斜角度，根据深度信息动态调整纹理坐标，
 * 创造出立体的视觉效果。
 *
 * 主要功能：
 * - 使用 Filament 引擎进行高性能 3D 渲染
 * - 自定义 GLSL 着色器实现视差算法
 * - 传感器数据处理和平滑
 * - 纹理管理和资源清理
 *
 * @param context Android 上下文，用于获取传感器服务
 * @param surfaceView 用于渲染的 SurfaceView
 */
class DepthRenderer(private val context: Context, private val surfaceView: SurfaceView) {
    // ========== Filament 渲染引擎核心组件 ==========
    /** Filament 渲染引擎实例 */
    private val engine = Engine.create()

    /** 渲染器，负责执行渲染命令 */
    private val renderer = engine.createRenderer()

    /** 场景对象，包含所有可渲染的实体 */
    private val scene = engine.createScene()

    /** 视图对象，定义渲染参数和后处理效果 */
    private val view = engine.createView()

    /** 相机实体ID */
    private val cameraEntity = EntityManager.get().create()

    /** 相机组件，定义视角和投影 */
    private val camera = engine.createCamera(cameraEntity)

    /** UI助手，处理Surface生命周期 */
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    // ========== 渲染资源 ==========
    /** 材质实例，用于设置着色器参数 */
    private var materialInstance: MaterialInstance? = null

    /** 彩色纹理，存储原始图片数据 */
    private var colorTexture: Texture? = null

    /** 深度纹理，存储深度图数据 */
    private var depthTexture: Texture? = null

    /** 交换链，管理帧缓冲 */
    private var swapChain: SwapChain? = null

    /** 可渲染对象的实体ID */
    private var renderable: Int = 0

    // ========== 传感器相关 ==========
    /** 传感器管理器 */
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** 旋转向量传感器，用于检测设备姿态 */
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** 平滑处理后的俯仰角（前后倾斜） */
    private var smoothedPitch = 0f

    /** 平滑处理后的翻滚角（左右倾斜） */
    private var smoothedRoll = 0f

    /** 上次传感器更新时间，用于限制更新频率 */
    private var lastUpdate = 0L

    // ========== 渲染控制 ==========
    /** 渲染状态标志 */
    private var isRendering = false

    /** 编舞者，用于同步渲染帧率 */
    private val choreographer = Choreographer.getInstance()

    // ========== 多层渲染相关 ==========
    /** 当前渲染模式 */
    private var renderMode: RenderMode = RenderMode.SINGLE_LAYER

    /** 多层渲染的层数 */
    private val layerCount = 6

    /** 多层渲染的材质实例列表 */
    private val layerMaterialInstances = mutableListOf<MaterialInstance>()

    /** 多层渲染的可渲染对象列表 */
    private val layerRenderables = mutableListOf<Int>()

    /** 顶点缓冲区，存储四边形的顶点数据 */
    private var vertexBuffer: VertexBuffer? = null

    /** 索引缓冲区，存储三角形索引 */
    private var indexBuffer: IndexBuffer? = null

    /**
     * 初始化渲染器
     *
     * 按顺序执行以下初始化步骤：
     * 1. 设置相机投影和位置
     * 2. 配置视图的色彩分级和光照
     * 3. 初始化UI助手和渲染循环
     * 4. 创建材质和几何体
     * 5. 启动传感器监听
     */
    init {
        setupCamera()
        view.scene = scene
        view.camera = camera

        // 配置渲染器色彩分级设置，优化视觉效果
        view.colorGrading = ColorGrading.Builder()
            .toneMapping(ColorGrading.ToneMapping.ACES)  // 使用ACES色调映射，提供电影级色彩
            .exposure(-0.3f) // 降低曝光，减少整体亮度，避免过曝
            .build(engine)

        // 禁用环境光遮蔽，保持图片原始亮度
        view.ambientOcclusion = View.AmbientOcclusion.NONE

        setupUiHelper()
        initializeRenderingSystem()
        setupSensor()
    }

    /**
     * 设置相机参数
     *
     * 配置正交投影相机，确保图片不会因为透视产生变形。
     * 使用固定的投影范围 [-1, 1] 确保四边形完全填充视口。
     */
    private fun setupCamera() {
        // 设置正交投影，范围为 [-1, 1]，近平面0，远平面10
        camera.setProjection(Camera.Projection.ORTHO, -1.0, 1.0, -1.0, 1.0, 0.0, 10.0)

        // 设置相机位置和朝向
        // 相机位置：(0, 0, 1) - 在Z轴正方向1个单位
        // 目标点：(0, 0, 0) - 原点
        // 上方向：(0, 1, 0) - Y轴正方向
        camera.lookAt(
            0.0, 0.0, 1.0,
            0.0, 0.0, 0.0,
            0.0, 1.0, 0.0
        )
    }

    /**
     * 初始化渲染系统
     *
     * 根据当前渲染模式初始化相应的渲染组件：
     * - 单层模式：创建单个材质和四边形
     * - 多层模式：创建多个深度层，每层有独立的材质和几何体
     */
    private fun initializeRenderingSystem() {
        when (renderMode) {
            RenderMode.SINGLE_LAYER -> {
                val material = createMaterial(engine)
                materialInstance = setupQuad(engine, material)
            }
            RenderMode.MULTI_LAYER -> {
                setupMultiLayerRendering()
            }
        }
    }

    /**
     * 设置多层渲染
     *
     * 创建多个深度层，每层对应不同的深度范围。
     * 从远到近依次渲染，实现正确的深度遮挡效果。
     */
    private fun setupMultiLayerRendering() {
        // 清理现有的多层渲染资源
        clearMultiLayerResources()

        // 为每一层创建材质和几何体
        for (layerIndex in 0 until layerCount) {
            val material = createLayerMaterial(engine, layerIndex)
            val materialInstance = setupLayerQuad(engine, material, layerIndex)
            layerMaterialInstances.add(materialInstance)
        }
    }

    /**
     * 创建分层材质
     *
     * 为特定深度层创建材质，只渲染该层对应深度范围内的像素。
     *
     * @param engine Filament引擎实例
     * @param layerIndex 层索引（0=最远层，layerCount-1=最近层）
     * @return 编译好的材质对象
     */
    private fun createLayerMaterial(engine: Engine, layerIndex: Int): Material {
        return try {
            MaterialBuilder.init()

            val materialBuilder = MaterialBuilder()
            val materialPackage = materialBuilder
                .name("ParallaxLayerMaterial_$layerIndex")
                .platform(MaterialBuilder.Platform.MOBILE)
                .shading(MaterialBuilder.Shading.UNLIT)
                .require(MaterialBuilder.VertexAttribute.UV0)
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        prepareMaterial(material);
                        
                        vec2 uv = getUV0();
                        uv.y = 1.0 - uv.y;
                        
                        // 从深度纹理采样获取深度值
                        float depth = texture(materialParams_depthTexture, uv).r;
                        
                        // 计算当前层的深度范围
                        float layerIndex = materialParams.layerIndex;
                        float totalLayers = materialParams.totalLayers;
                        float layerStart = layerIndex / totalLayers;
                        float layerEnd = (layerIndex + 1.0) / totalLayers;
                        
                        // 只渲染属于当前深度层的像素
                        if (depth < layerStart || depth >= layerEnd) {
                            discard;
                        }
                        
                        // 计算视差偏移，远层偏移小，近层偏移大
                        float layerDepth = (layerStart + layerEnd) * 0.5;
                        vec2 offset = layerDepth * materialParams.angles * materialParams.scaleFactor;
                        
                        vec2 newTexCoord = uv + offset;
                        newTexCoord = clamp(newTexCoord, vec2(0.0), vec2(1.0));
                        
                        vec4 color = texture(materialParams_colorTexture, newTexCoord);
                        material.baseColor = color;
                    }
                """
                )
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "colorTexture"
                )
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "depthTexture"
                )
                .uniformParameter(MaterialBuilder.UniformType.FLOAT2, "angles")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "scaleFactor")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "layerIndex")
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "totalLayers")
                .build(engine)

            Material.Builder()
                .payload(materialPackage.getBuffer(), materialPackage.getBuffer().remaining())
                .build(engine)
        } catch (e: Exception) {
            Log.e("MaterialBuilder", "分层材质编译失败: ${e.message}")
            throw e
        }
    }

    /**
     * 为特定层创建四边形几何体
     *
     * @param engine Filament引擎实例
     * @param material 要应用的材质
     * @param layerIndex 层索引
     * @return 材质实例
     */
    private fun setupLayerQuad(engine: Engine, material: Material, layerIndex: Int): MaterialInstance {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f,
            1f, -1f, 0f, 1f, 1f,
            -1f, 1f, 0f, 0f, 0f,
            1f, 1f, 0f, 1f, 0f
        )

        val indices = shortArrayOf(0, 1, 2, 1, 3, 2)

        // 如果是第一层，创建共享的顶点和索引缓冲区
        if (layerIndex == 0) {
            vertexBuffer = VertexBuffer.Builder()
                .vertexCount(4)
                .bufferCount(1)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION,
                    0,
                    VertexBuffer.AttributeType.FLOAT3,
                    0,
                    20
                )
                .attribute(
                    VertexBuffer.VertexAttribute.UV0,
                    0,
                    VertexBuffer.AttributeType.FLOAT2,
                    12,
                    20
                )
                .build(engine)
            vertexBuffer?.setBufferAt(engine, 0, FloatBuffer.wrap(vertices))

            indexBuffer = IndexBuffer.Builder()
                .indexCount(6)
                .bufferType(IndexBuffer.Builder.IndexType.USHORT)
                .build(engine)
            indexBuffer?.setBuffer(engine, ShortBuffer.wrap(indices))
        }

        val materialInstance = material.createInstance()

        // 为每一层创建独立的可渲染实体
        val layerRenderable = EntityManager.get().create()
        
        // 设置不同的Z坐标，确保正确的渲染顺序（远层在后，近层在前）
        val zOffset = (layerCount - layerIndex - 1) * 0.1f
        
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, zOffset, 1f, 1f, zOffset + 0.1f))
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer!!,
                indexBuffer!!
            )
            .material(0, materialInstance)
            .build(engine, layerRenderable)

        // 设置实体的变换矩阵，调整Z位置
        val transform = FloatArray(16)
        android.opengl.Matrix.setIdentityM(transform, 0)
        transform[14] = zOffset  // 设置Z坐标
        
        val transformManager = engine.transformManager
        transformManager.setTransform(transformManager.getInstance(layerRenderable), transform)

        scene.addEntity(layerRenderable)
        layerRenderables.add(layerRenderable)

        // 设置层参数
        materialInstance.setParameter("layerIndex", layerIndex.toFloat())
        materialInstance.setParameter("totalLayers", layerCount.toFloat())

        return materialInstance
    }

    /**
     * 清理多层渲染资源
     */
    private fun clearMultiLayerResources() {
        // 清理材质实例
        layerMaterialInstances.forEach { materialInstance ->
            engine.destroyMaterialInstance(materialInstance)
        }
        layerMaterialInstances.clear()

        // 清理可渲染实体
        layerRenderables.forEach { renderable ->
            scene.removeEntity(renderable)
            engine.destroyEntity(renderable)
        }
        layerRenderables.clear()
    }

    /**
     * 设置UI助手和渲染回调
     *
     * UI助手负责管理Surface的生命周期，处理窗口变化、大小调整等事件。
     * 同时启动渲染循环。
     */
    private fun setupUiHelper() {
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            /**
             * 当原生窗口发生变化时调用
             * 重新创建交换链以适应新的Surface
             */
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
            }

            /**
             * 当Surface分离时调用
             * 清理交换链资源
             */
            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = null
            }

            /**
             * 当视图大小发生变化时调用
             * 保持固定的正交投影范围，确保图片完全填充渲染区域
             */
            override fun onResized(width: Int, height: Int) {
                // 使用固定的正交投影范围，让四边形完全撑满渲染区域
                camera.setProjection(Camera.Projection.ORTHO, -1.0, 1.0, -1.0, 1.0, 0.0, 10.0)
            }
        }

        // 将UI助手绑定到SurfaceView
        uiHelper.attachTo(surfaceView)
        // 开始渲染循环
        startRendering()
    }

    /**
     * 创建视差效果材质
     *
     * 使用自定义GLSL着色器实现基于深度图的视差效果。
     * 着色器根据深度值和设备倾斜角度动态调整纹理坐标，
     * 创造出立体的视觉效果。
     *
     * 着色器工作原理：
     * 1. 获取当前像素的UV坐标
     * 2. 从深度图采样获取深度值
     * 3. 根据深度值和倾斜角度计算偏移量
     * 4. 使用偏移后的坐标从彩色纹理采样
     *
     * @param engine Filament引擎实例
     * @return 编译好的材质对象
     */
    private fun createMaterial(engine: Engine): Material {
        return try {
            // 初始化MaterialBuilder静态组件
            MaterialBuilder.init()

            val materialBuilder = MaterialBuilder()
            val materialPackage = materialBuilder
                .name("ParallaxMaterial")  // 材质名称
                .platform(MaterialBuilder.Platform.MOBILE)  // 移动平台优化
                .shading(MaterialBuilder.Shading.UNLIT)  // 无光照着色，直接显示纹理颜色
                .require(MaterialBuilder.VertexAttribute.UV0)  // 需要UV坐标
                .material(
                    """
                    void material(inout MaterialInputs material) {
                        prepareMaterial(material);
                        
                        // 获取当前片段的UV坐标
                        vec2 uv = getUV0();
                        // 翻转Y坐标以修正OpenGL和Android坐标系差异导致的上下颠倒
                        uv.y = 1.0 - uv.y;
                        
                        // 从深度纹理采样获取深度值（0.0=远景，1.0=近景）
                        float depth = texture(materialParams_depthTexture, uv).r;
                        
                        // 根据深度值、设备倾斜角度和缩放因子计算纹理坐标偏移
                        // angles.x = roll（左右倾斜），angles.y = pitch（前后倾斜）
                        vec2 offset = depth * materialParams.angles * materialParams.scaleFactor;
                        
                        // 计算新的纹理坐标
                        vec2 newTexCoord = uv + offset;
                        // 限制坐标范围在[0,1]内，避免采样越界
                        newTexCoord = clamp(newTexCoord, vec2(0.0), vec2(1.0));
                        
                        // 使用偏移后的坐标从彩色纹理采样
                        vec4 color = texture(materialParams_colorTexture, newTexCoord);
                        
                        // 设置最终颜色，保持原始颜色，通过全局光照控制亮度
                        material.baseColor = color;
                    }
                """
                )
                // 定义材质参数：彩色纹理采样器
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "colorTexture"
                )
                // 定义材质参数：深度纹理采样器
                .samplerParameter(
                    MaterialBuilder.SamplerType.SAMPLER_2D,
                    MaterialBuilder.SamplerFormat.FLOAT,
                    MaterialBuilder.ParameterPrecision.DEFAULT,
                    "depthTexture"
                )
                // 定义材质参数：设备倾斜角度（roll, pitch）
                .uniformParameter(MaterialBuilder.UniformType.FLOAT2, "angles")
                // 定义材质参数：视差效果强度缩放因子
                .uniformParameter(MaterialBuilder.UniformType.FLOAT, "scaleFactor")
                .build(engine)

            // 创建最终的材质对象
            Material.Builder()
                .payload(materialPackage.getBuffer(), materialPackage.getBuffer().remaining())
                .build(engine)
        } catch (e: Exception) {
            Log.e("MaterialBuilder", "材质编译失败: ${e.message}")
            throw e
        }
    }

    /**
     * 创建渲染四边形
     *
     * 创建一个覆盖整个屏幕的四边形，用于显示视差效果。
     * 四边形包含位置坐标和纹理坐标，使用三角形索引进行渲染。
     *
     * @param engine Filament引擎实例
     * @param material 要应用的材质
     * @return 材质实例，用于后续参数设置
     */
    private fun setupQuad(engine: Engine, material: Material): MaterialInstance {
        // 定义四边形顶点数据：位置(x,y,z) + 纹理坐标(u,v)
        // 覆盖整个标准化设备坐标系 [-1, 1]
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f, // 左下角：位置(-1,-1,0), 纹理坐标(0,1)
            1f, -1f, 0f, 1f, 1f,  // 右下角：位置(1,-1,0), 纹理坐标(1,1)
            -1f, 1f, 0f, 0f, 0f,  // 左上角：位置(-1,1,0), 纹理坐标(0,0)
            1f, 1f, 0f, 1f, 0f    // 右上角：位置(1,1,0), 纹理坐标(1,0)
        )

        // 定义三角形索引，使用两个三角形组成四边形
        // 第一个三角形：0-1-2（左下-右下-左上）
        // 第二个三角形：1-3-2（右下-右上-左上）
        val indices = shortArrayOf(0, 1, 2, 1, 3, 2)

        // 创建顶点缓冲区
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(4)  // 4个顶点
            .bufferCount(1)  // 1个缓冲区
            // 位置属性：3个float，从偏移0开始，步长20字节
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                20
            )
            // UV坐标属性：2个float，从偏移12开始，步长20字节
            .attribute(
                VertexBuffer.VertexAttribute.UV0,
                0,
                VertexBuffer.AttributeType.FLOAT2,
                12,
                20
            )
            .build(engine)
        // 将顶点数据上传到GPU
        vertexBuffer?.setBufferAt(engine, 0, FloatBuffer.wrap(vertices))

        // 创建索引缓冲区
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(6)  // 6个索引（2个三角形 × 3个顶点）
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)  // 使用16位无符号整数
            .build(engine)
        // 将索引数据上传到GPU
        indexBuffer.setBuffer(engine, ShortBuffer.wrap(indices))

        // 创建材质实例
        val materialInstance = material.createInstance()

        // 创建可渲染实体
        renderable = EntityManager.get().create()
        RenderableManager.Builder(1)
            .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))  // 设置包围盒
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer!!,
                indexBuffer
            )  // 设置几何体
            .material(0, materialInstance)  // 绑定材质
            .build(engine, renderable)

        // 将实体添加到场景中
        scene.addEntity(renderable)
        return materialInstance
    }

    private fun updateQuadAspectRatio(aspectRatio: Float) {
        // 四边形始终撑满整个渲染区域，保持原始的全屏四边形
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f, // 左下
            1f, -1f, 0f, 1f, 1f,  // 右下
            -1f, 1f, 0f, 0f, 0f,  // 左上
            1f, 1f, 0f, 1f, 0f    // 右上
        )

        vertexBuffer?.setBufferAt(engine, 0, FloatBuffer.wrap(vertices))
    }

    /**
     * 设置传感器监听
     *
     * 注册旋转向量传感器监听器，实时获取设备姿态变化。
     * 使用低通滤波器对传感器数据进行平滑处理，减少抖动。
     * 将处理后的角度数据传递给着色器，实现视差效果。
     */
    private fun setupSensor() {
        if (rotationSensor == null) {
            Log.w("DepthRenderer", "设备不支持旋转向量传感器")
            return
        }

        // 用于存储旋转矩阵和方向角度的数组
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)

        sensorListener = object : SensorEventListener {
            /**
             * 传感器数据变化回调
             *
             * 处理旋转向量传感器数据，计算设备的俯仰角和翻滚角，
             * 并将其转换为着色器可用的参数。
             */
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                    val now = System.currentTimeMillis()
                    // 可选：限制更新频率以优化性能
                    // if (now - lastUpdate < 16) return // 约60fps
                    lastUpdate = now

                    // 从旋转向量计算旋转矩阵
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    // 从旋转矩阵计算设备方向角度
                    // orientationAngles[0] = azimuth (方位角)
                    // orientationAngles[1] = pitch (俯仰角，前后倾斜)
                    // orientationAngles[2] = roll (翻滚角，左右倾斜)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)

                    // 使用低通滤波器平滑处理角度数据，减少传感器噪声
                    // 滤波公式：new_value = old_value * α + raw_value * (1-α)
                    // α = 0.9 表示90%保留旧值，10%采用新值，实现平滑效果
                    smoothedPitch = smoothedPitch * 0.9f + orientationAngles[1] * 0.1f
                    smoothedRoll = smoothedRoll * 0.9f + orientationAngles[2] * 0.1f

                    // 更新着色器参数
                    // roll取反是为了修正视差方向，使倾斜方向与视觉效果一致
                    materialInstance?.setParameter("angles", -smoothedRoll, smoothedPitch)

                    layerMaterialInstances?.let {
                        it.forEach { materialInstance ->
                            materialInstance.setParameter("angles", -smoothedRoll, smoothedPitch)
                        }
                    }
                }
            }

            /**
             * 传感器精度变化回调（暂未使用）
             */
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // 注册传感器监听器，使用最快的采样频率
        sensorManager.registerListener(
            sensorListener,
            rotationSensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )
    }

    /**
     * 启动渲染循环
     *
     * 使用Choreographer实现与显示器刷新率同步的渲染循环。
     * 这种方式比协程更适合图形渲染，能够提供更稳定的帧率和更低的延迟。
     *
     * 渲染流程：
     * 1. 检查渲染状态是否激活
     * 2. 设置视口大小匹配SurfaceView
     * 3. 开始新的渲染帧
     * 4. 渲染当前视图（包含视差效果）
     * 5. 结束帧并交换缓冲区
     * 6. 调度下一帧回调
     */
    private fun startRendering() {
        if (isRendering) return
        isRendering = true

        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!isRendering) return

                swapChain?.let { swapChain ->
                    // 设置视口大小匹配当前SurfaceView尺寸
                    val viewport = Viewport(0, 0, surfaceView.width, surfaceView.height)
                    // 开始新的渲染帧，传入时间戳用于动画和性能分析
                    if (renderer.beginFrame(swapChain, frameTimeNanos)) {
                        view.viewport = viewport
                        // 渲染视图（包含所有3D对象和视差效果）
                        renderer.render(view)
                        // 结束帧并交换前后缓冲区
                        renderer.endFrame()
                    }
                }

                // 调度下一帧回调，实现连续渲染
                choreographer.postFrameCallback(this)
            }
        }

        // 开始第一帧的渲染
        choreographer.postFrameCallback(frameCallback)
    }

    /**
     * 加载彩色图片和深度图纹理
     *
     * 这是渲染器的主要外部接口，用于加载图片数据并创建GPU纹理。
     * 支持动态更换图片，会自动清理旧的纹理资源。
     *
     * @param colorBitmap 彩色图片位图，用于显示的原始图像
     * @param depthBitmap 深度图位图，灰度图像，白色=近景，黑色=远景
     * @param imageAspectRatio 图片宽高比，用于调整渲染区域（当前版本保持全屏显示）
     */
    fun loadTextures(colorBitmap: Bitmap, depthBitmap: Bitmap, imageAspectRatio: Float = 1.0f) {
        // 释放旧纹理资源，避免内存泄漏
        colorTexture?.let { engine.destroyTexture(it) }
        depthTexture?.let { engine.destroyTexture(it) }

        try {
            // ========== 创建彩色纹理 ==========
            colorTexture = Texture.Builder()
                .width(colorBitmap.width)  // 纹理宽度
                .height(colorBitmap.height)  // 纹理高度
                .format(Texture.InternalFormat.RGBA8)  // RGBA格式，每通道8位
                .build(engine)

            // 将位图数据转换为GPU可用的字节缓冲区
            val colorBuffer = bitmapToByteBuffer(colorBitmap, "RGBA")
            // 上传纹理数据到GPU
            colorTexture?.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(colorBuffer, Texture.Format.RGBA, Texture.Type.UBYTE)
            )

            // ========== 创建深度纹理 ==========
            depthTexture = Texture.Builder()
                .width(depthBitmap.width)  // 深度图宽度
                .height(depthBitmap.height)  // 深度图高度
                .format(Texture.InternalFormat.R8)  // 单通道红色格式，8位精度
                .build(engine)

            // 将深度图转换为单通道数据
            val depthBuffer = bitmapToByteBuffer(depthBitmap, "A")
            // 上传深度纹理数据到GPU
            depthTexture?.setImage(
                engine,
                0,
                Texture.PixelBufferDescriptor(depthBuffer, Texture.Format.R, Texture.Type.UBYTE)
            )

            // ========== 绑定纹理到材质着色器 ==========
            when (renderMode) {
                RenderMode.SINGLE_LAYER -> {
                    // 单层渲染：绑定到单个材质实例
                    materialInstance?.let { instance ->
                        bindTexturesToMaterial(instance)
                    }
                }
                RenderMode.MULTI_LAYER -> {
                    // 多层渲染：绑定到所有层的材质实例
                    layerMaterialInstances.forEach { instance ->
                        bindTexturesToMaterial(instance)
                    }
                }
            }

            // 根据图片比例更新渲染四边形（当前版本保持全屏）
            updateQuadAspectRatio(imageAspectRatio)

            Log.d("DepthRenderer", "纹理加载成功")
        } catch (e: Exception) {
            Log.e("DepthRenderer", "纹理加载失败: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * 切换渲染模式
     *
     * 允许在运行时动态切换单层渲染和多层渲染模式。
     * 切换时会重新初始化渲染系统，并保持当前的纹理数据。
     *
     * @param newMode 新的渲染模式
     */
    fun setRenderMode(newMode: RenderMode) {
        if (renderMode == newMode) return
        
        // 清理当前模式的资源
        when (renderMode) {
            RenderMode.SINGLE_LAYER -> {
                materialInstance?.let { engine.destroyMaterialInstance(it) }
                materialInstance = null
                if (renderable != 0) {
                    engine.destroyEntity(renderable)
                    renderable = 0
                }
            }
            RenderMode.MULTI_LAYER -> {
                clearMultiLayerResources()
            }
        }
        
        // 切换到新模式
        renderMode = newMode
        
        // 重新初始化渲染系统
        initializeRenderingSystem()
        
        // 如果已有纹理数据，重新绑定
        if (colorTexture != null && depthTexture != null) {
            when (renderMode) {
                RenderMode.SINGLE_LAYER -> {
                    materialInstance?.let { instance ->
                        bindTexturesToMaterial(instance)
                    }
                }
                RenderMode.MULTI_LAYER -> {
                    layerMaterialInstances.forEach { instance ->
                        bindTexturesToMaterial(instance)
                    }
                }
            }
        }
    }

    /**
     * 绑定纹理到指定的材质实例
     *
     * 统一处理彩色纹理和深度纹理的绑定逻辑，包括纹理采样器的配置。
     * 这个方法被单层和多层渲染模式共同使用。
     *
     * @param instance 要绑定纹理的材质实例
     */
    private fun bindTexturesToMaterial(instance: MaterialInstance) {
        // 绑定彩色纹理
        colorTexture?.let { texture ->
            instance.setParameter("colorTexture", texture, TextureSampler().apply {
                magFilter = TextureSampler.MagFilter.LINEAR  // 放大时使用线性插值
                minFilter = TextureSampler.MinFilter.LINEAR  // 缩小时使用线性插值
            })
        }
        
        // 绑定深度纹理
        depthTexture?.let { texture ->
            instance.setParameter("depthTexture", texture, TextureSampler().apply {
                magFilter = TextureSampler.MagFilter.LINEAR  // 平滑的深度插值
                minFilter = TextureSampler.MinFilter.LINEAR
            })
        }
        
        // 设置视差效果强度，值越大效果越明显
        instance.setParameter("scaleFactor", 0.08f)
    }

    /**
     * 将Android Bitmap转换为GPU纹理所需的字节缓冲区
     *
     * 这个方法处理不同格式的像素数据转换：
     * - RGBA格式：用于彩色纹理，保留所有颜色通道
     * - A格式：用于深度纹理，只提取Alpha通道作为深度值
     *
     * @param bitmap 要转换的位图
     * @param format 目标格式 "RGBA"=彩色纹理, "A"=深度纹理
     * @return 包含像素数据的直接内存缓冲区，可直接传递给GPU
     */
    private fun bitmapToByteBuffer(bitmap: Bitmap, format: String = "RGBA"): ByteBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerPixel = if (format == "RGBA") 4 else 1
        val bufferSize = width * height * bytesPerPixel

        // 分配直接内存缓冲区，避免JVM垃圾回收影响GPU访问
        val buffer = ByteBuffer.allocateDirect(bufferSize)
        buffer.order(ByteOrder.nativeOrder())  // 使用本机字节序，提高性能

        // 提取位图的所有像素数据
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 根据目标格式转换像素数据
        for (pixel in pixels) {
            if (format == "RGBA") {
                // 提取RGBA四个通道，Android像素格式为ARGB
                buffer.put((pixel shr 16 and 0xFF).toByte()) // R 红色通道
                buffer.put((pixel shr 8 and 0xFF).toByte())  // G 绿色通道
                buffer.put((pixel and 0xFF).toByte())        // B 蓝色通道
                buffer.put((pixel shr 24 and 0xFF).toByte()) // A 透明度通道
            } else {
                // 只提取色彩通道作为深度值
                buffer.put((pixel and 0xFFFFFF).toByte())
            }
        }

        buffer.rewind()  // 重置位置指针到开始
        return buffer
    }

    private var sensorListener: SensorEventListener? = null

    /**
     * 销毁渲染器并清理所有资源
     *
     * 这个方法必须在Activity销毁时调用，以避免内存泄漏和GPU资源泄漏。
     * 清理顺序很重要：先清理高级资源，再清理底层资源，最后销毁引擎。
     *
     * 清理步骤：
     * 1. 停止渲染循环和传感器监听
     * 2. 清理纹理和材质资源
     * 3. 清理几何体和渲染对象
     * 4. 清理Filament核心组件
     * 5. 销毁渲染引擎
     */
    fun destroy() {
        // 停止渲染循环
        isRendering = false

        // 注销传感器监听器
        sensorListener?.let { sensorManager.unregisterListener(it) }

        // ========== 清理渲染资源 ==========
        // 清理纹理资源
        colorTexture?.let { engine.destroyTexture(it) }
        depthTexture?.let { engine.destroyTexture(it) }

        // 清理材质资源
        when (renderMode) {
            RenderMode.SINGLE_LAYER -> {
                materialInstance?.let { engine.destroyMaterialInstance(it) }
            }
            RenderMode.MULTI_LAYER -> {
                clearMultiLayerResources()
            }
        }

        // 清理几何体资源
        vertexBuffer?.let { engine.destroyVertexBuffer(it) }
        indexBuffer?.let { engine.destroyIndexBuffer(it) }

        // 清理渲染实体
        when (renderMode) {
            RenderMode.SINGLE_LAYER -> {
                if (renderable != 0) {
                    engine.destroyEntity(renderable)
                }
            }
            RenderMode.MULTI_LAYER -> {
                // 多层渲染实体已在clearMultiLayerResources中清理
            }
        }

        // ========== 清理Filament核心组件 ==========
        // 按依赖关系顺序清理
        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(cameraEntity)

        // 分离UI助手
        uiHelper.detach()

        // 最后销毁引擎（必须在所有其他资源清理后）
        engine.destroy()

        Log.d("DepthRenderer", "渲染器已销毁，所有资源已清理")
    }
}
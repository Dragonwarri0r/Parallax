基于 Filament 1.57.1 的 Android 景深效果技术文档（无外部材质资源）
1. 需求概述
本项目旨在开发一个 Android 应用，支持以下功能：

从相册选择照片：用户从设备相册选择一张彩色照片。
获取深度图：通过外部 API 上传照片，获取对应的灰度深度图（单通道，8 位）。
景深效果展示：使用 Filament 1.57.1 渲染引擎，结合设备陀螺仪数据，实时渲染彩色图片和深度图，生成视差（Parallax）效果，模拟景深。
无外部资源：不依赖预编译的材质文件，通过代码动态生成材质。

2. 技术方案
2.1 技术选型

渲染引擎：Filament 1.57.1，基于 Vulkan（Android 7.0+），提供高性能渲染和现代化 API。
相册选择：Android 的 ActivityResultContracts.PickVisualMedia。
网络请求：OkHttp 用于上传照片并接收深度图。
传感器：Android SensorManager 获取 TYPE_ROTATION_VECTOR 数据。
开发环境：
Android Studio（Flamingo 或更高版本）
Kotlin
最低 API：24（Android 7.0）
Android NDK 25.1 或更高
Java 17



2.2 系统架构

UI 层：包含相册选择按钮、照片预览和 Filament 渲染视图（SurfaceView）。
业务逻辑层：
相册选择：处理照片 URI，转换为 Bitmap。
网络模块：上传照片至深度图 API，接收灰度深度图。
传感器模块：监听陀螺仪数据，计算倾斜角度。


渲染层：使用 Filament 1.57.1，动态创建材质，加载彩色图片和深度图为纹理，实时渲染视差效果。

2.3 工作流程

用户点击“选择照片”按钮，打开相册。
选择照片后，显示预览并上传至深度图 API。
API 返回灰度深度图后，加载彩色图片和深度图为 Filament 纹理。
初始化陀螺仪监听，实时更新设备倾斜角度。
Filament 渲染全屏四边形，动态材质根据深度图和倾斜角度计算像素偏移，生成景深效果。

3. 实现步骤
3.1 项目设置

添加依赖：在 app/build.gradle 中添加 Filament 1.57.1 和 OkHttp 依赖：

implementation 'com.google.android.filament:filament-android:1.57.1'
implementation 'com.google.android.filament:gltfio-android:1.57.1'
implementation 'com.squareup.okhttp3:okhttp:4.12.0'


权限配置：在 AndroidManifest.xml 中添加权限：

<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.INTERNET" />


启用 Vulkan：在 build.gradle 中设置：

minSdkVersion 24


动态请求权限：在 Android 6.0+ 上动态请求存储权限：

if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
    requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
}

3.2 从相册选择照片
使用 ActivityResultLauncher 启动相册选择，转换为 Bitmap。
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var renderer: DepthRenderer
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { loadImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 Filament 渲染视图
        renderer = DepthRenderer(this, SurfaceView(this).also {
            binding.filamentContainer.addView(it)
        })

        // 选择照片按钮
        binding.btnSelectPhoto.setOnClickListener {
            pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // 检查陀螺仪支持
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) {
            Toast.makeText(this, "设备不支持陀螺仪", Toast.LENGTH_LONG

).show()
        }
    }

    private fun loadImage(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val bitmap = BitmapFactory.decodeStream(inputStream)
            binding.imagePreview.setImageBitmap(bitmap)
            uploadImageForDepthMap(uri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer.destroy()
    }
}

3.3 调用 API 获取深度图
使用 OkHttp 上传照片，接收灰度深度图。
private fun uploadImageForDepthMap(uri: Uri) {
    val file = contentResolver.openInputStream(uri)?.use { input ->
        File(cacheDir, "temp_image.jpg").apply {
            outputStream().use { output -> input.copyTo(output) }
        }
    } ?: return

    val requestBody = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("image", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        .build()

    val request = Request.Builder()
        .url("https://api.example.com/depth") // 替换为实际 API
        .post(requestBody)
        .build()

    OkHttpClient().newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            runOnUiThread { Toast.makeText(this@MainActivity, "上传失败", Toast.LENGTH_SHORT).show() }
        }

        override fun onResponse(call: Call, response: Response) {
            if (response.isSuccessful) {
                response.body?.byteStream()?.use { input ->
                    val depthBitmap = BitmapFactory.decodeStream(input)
                    runOnUiThread {
                        val colorBitmap = binding.imagePreview.drawable.toBitmap()
                        val scaledDepthBitmap = Bitmap.createScaledBitmap(
                            depthBitmap, colorBitmap.width, colorBitmap.height, true
                        )
                        renderer.loadTextures(colorBitmap, scaledDepthBitmap)
                        depthBitmap.recycle()
                    }
                }
            } else {
                runOnUiThread { Toast.makeText(this@MainActivity, "API 错误: ${response.code}", Toast.LENGTH_SHORT).show() }
            }
        }
    })
}

3.4 动态创建材质与渲染逻辑
使用 Filament 1.57.1 的 MaterialBuilder 动态生成材质，结合陀螺仪数据和纹理实现景深效果。
3.4.1 动态材质创建
定义顶点和片段着色器，使用 uniformParameter 设置采样器。
import com.google.android.filament.*

private fun createMaterial(engine: Engine): Material {
    return try {
        MaterialBuilder()
            .name("ParallaxMaterial")
            .targetApi(MaterialBuilder.TargetApi.VULKAN)
            .shading(MaterialBuilder.Shading.UNLIT)
            .vertexShader("""
                #version 300 es
                layout(location = 0) in vec4 aPosition;
                layout(location = 1) in vec2 aTexCoord;
                out vec2 vTexCoord;

                void main() {
                    vTexCoord = aTexCoord;
                    gl_Position = aPosition;
                }
            """.trimIndent())
            .fragmentShader("""
                #version 300 es
                precision mediump float;
                uniform sampler2D colorTexture;
                uniform sampler2D depthTexture;
                uniform vec2 angles;
                uniform float scaleFactor;
                in vec2 vTexCoord;
                out vec4 fragColor;

                void main() {
                    float depth = texture(depthTexture, vTexCoord).r;
                    vec2 offset = depth * angles * scaleFactor;
                    vec2 newTexCoord = vTexCoord + offset;
                    newTexCoord = clamp(newTexCoord, vec2(0.0), vec2(1.0));
                    fragColor = texture(colorTexture, newTexCoord);
                }
            """.trimIndent())
            .uniformParameter(MaterialBuilder.UniformType.SAMPLER, "colorTexture")
            .uniformParameter(MaterialBuilder.UniformType.SAMPLER, "depthTexture")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT2, "angles")
            .uniformParameter(MaterialBuilder.UniformType.FLOAT, "scaleFactor")
            .build(engine)
    } catch (e: Exception) {
        Log.e("MaterialBuilder", "材质编译失败: ${e.message}")
        throw e
    }
}


更新点：
使用 uniformParameter(MaterialBuilder.UniformType.SAMPLER, ...) 替换 samplerParameter，符合 1.57.1 API。
保留错误捕获以便调试。



3.4.2 加载纹理
加载彩色图片和深度图，使用 ETC2 压缩。
private var colorTexture: Texture? = null
private var depthTexture: Texture? = null

private fun loadTextures(engine: Engine, colorBitmap: Bitmap, depthBitmap: Bitmap, materialInstance: MaterialInstance) {
    // 释放旧纹理
    colorTexture?.let { engine.destroyTexture(it) }
    depthTexture?.let { engine.destroyTexture(it) }

    // 创建彩色纹理
    colorTexture = Texture.Builder()
        .width(colorBitmap.width)
        .height(colorBitmap.height)
        .format(Texture.InternalFormat.ETC2_EAC_RGBA8)
        .build(engine)
    colorTexture?.setImage(
        engine, 0,
        PixelBufferDescriptor(
            bitmapToByteBuffer(colorBitmap),
            PixelBufferDescriptor.Format.RGBA,
            PixelBufferDescriptor.Type.UBYTE
        )
    )

    // 创建深度纹理
    depthTexture = Texture.Builder()
        .width(depthBitmap.width)
        .height(depthBitmap.height)
        .format(Texture.InternalFormat.ETC2_EAC_R8)
        .build(engine)
    depthTexture?.setImage(
        engine, 0,
        PixelBufferDescriptor(
            bitmapToByteBuffer(depthBitmap),
            PixelBufferDescriptor.Format.R,
            PixelBufferDescriptor.Type.UBYTE
        )
    )

    // 绑定纹理和参数
    materialInstance.setParameter("colorTexture", colorTexture, TextureSampler().apply {
        magFilter = TextureSampler.MagFilter.LINEAR
        minFilter = TextureSampler.MinFilter.LINEAR
    })
    materialInstance.setParameter("depthTexture", depthTexture, TextureSampler().apply {
        magFilter = TextureSampler.MagFilter.LINEAR
        minFilter = TextureSampler.MinFilter.LINEAR
    })
    materialInstance.setParameter("scaleFactor", 0.05f)
}

// 辅助函数：将 Bitmap 转换为 ByteBuffer
private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
    val bytes = ByteBuffer.allocateDirect(bitmap.byteCount)
    bitmap.copyPixelsToBuffer(bytes)
    bytes.rewind()
    return bytes
}

3.4.3 创建全屏四边形
创建四边形，符合 1.57.1 的渲染要求。
private fun setupQuad(engine: Engine, material: Material): MaterialInstance {
    val vertices = floatArrayOf(
        -1f, -1f, 0f, 0f, 1f, // 左下
        1f, -1f, 0f, 1f, 1f, // 右下
        -1f, 1f, 0f, 0f, 0f, // 左上
        1f, 1f, 0f, 1f, 0f   // 右上
    )
    val indices = shortArrayOf(0, 1, 2, 1, 3, 2)

    val vertexBuffer = VertexBuffer.Builder()
        .vertexCount(4)
        .bufferCount(1)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 20)
        .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 12, 20)
        .build(engine)
    vertexBuffer.setBufferAt(engine, 0, FloatBuffer.wrap(vertices))

    val indexBuffer = IndexBuffer.Builder()
        .indexCount(6)
        .bufferType(IndexBuffer.Builder.IndexType.USHORT)
        .build(engine)
    indexBuffer.setBuffer(engine, ShortBuffer.wrap(indices))

    val materialInstance = material.createInstance()
    val renderable = EntityManager.get().create()
    RenderableManager.Builder(1)
        .boundingBox(Box(0f, 0f, 0f, 1f, 1f, 1f))
        .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer)
        .material(0, materialInstance)
        .build(engine, renderable)

    scene.addEntity(renderable)
    return materialInstance
}


说明：未使用 setCulling()，因为 1.57.1 默认禁用剔除，符合需求。

3.4.4 渲染循环
使用 Choreographer 和 SwapChain。
private fun startRendering(engine: Engine, renderer: Renderer, camera: Camera, scene: Scene, surfaceView: SurfaceView) {
    val swapChain = engine.createSwapChain(surfaceView.holder.surface)
    val choreographer = Choreographer.getInstance()
    choreographer.postFrameCallback(object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val viewport = Viewport(0, 0, surfaceView.width, surfaceView.height)
            if (renderer.beginFrame(swapChain, viewport)) {
                renderer.render(camera, scene)
                renderer.endFrame()
            }
            choreographer.postFrameCallback(this)
        }
    })
}

3.4.5 陀螺仪监听
监听设备姿态，平滑更新角度。
private fun setupSensor(context: Context, materialInstance: MaterialInstance) {
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    val rotationMatrix = FloatArray(9)
    val orientationAngles = FloatArray(3)
    var smoothedPitch = 0f
    var smoothedRoll = 0f
    var lastUpdate = 0L

    sensorManager.registerListener(object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                val now = System.currentTimeMillis()
                if (now - lastUpdate < 16) return
                lastUpdate = now

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                smoothedPitch = smoothedPitch * 0.9f + orientationAngles[1] * 0.1f
                smoothedRoll = smoothedRoll * 0.9f + orientationAngles[2] * 0.1f
                materialInstance.setParameter("angles", floatArrayOf(smoothedPitch, smoothedRoll))
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }, rotationSensor, SensorManager.SENSOR_DELAY_FASTEST)
}

3.4.6 完整渲染器类
整合所有逻辑。
class DepthRenderer(private val context: Context, private val surfaceView: SurfaceView) {
    private val engine = Engine.create()
    private val renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val camera = engine.createCamera(engine.getCameraComponent(EntityManager.get().create()))
    private var materialInstance: MaterialInstance? = null
    private var colorTexture: Texture? = null
    private var depthTexture: Texture? = null

    init {
        setupCamera()
        val material = createMaterial(engine)
        materialInstance = setupQuad(engine, material)
        startRendering(engine, renderer, camera, scene, surfaceView)
        materialInstance?.let { setupSensor(context, it) }
    }

    private fun setupCamera() {
        camera.setProjection(Camera.Projection.ORTHO, -1.0, 1.0, -1.0, 1.0, 0.0, 10.0)
        camera.lookAt(floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
    }

    fun loadTextures(colorBitmap: Bitmap, depthBitmap: Bitmap) {
        materialInstance?.let { loadTextures(engine, colorBitmap, depthBitmap, it) }
        colorBitmap.recycle()
        depthBitmap.recycle()
    }

    fun destroy() {
        engine.destroyTexture(colorTexture)
        engine.destroyTexture(depthTexture)
        engine.destroyEntity(scene.getEntities().firstOrNull() ?: 0)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroyCameraComponent(camera.entity)
        engine.destroy()
    }
}

3.5 布局文件
res/layout/activity_main.xml：
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">

    <Button
        android:id="@+id/btn_select_photo"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="选择照片" />

    <ImageView
        android:id="@+id/image_preview"
        android:layout_width="match_parent"
        android:layout_height="200dp"
        android:scaleType="centerCrop" />

    <FrameLayout
        android:id="@+id/filament_container"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

</LinearLayout>

4. 性能优化

纹理压缩：
使用 ETC2_EAC_RGBA8 和 ETC2_EAC_R8 压缩纹理。


降低分辨率：
在加载深度图前下采样：val scaledDepthBitmap = Bitmap.createScaledBitmap(depthBitmap, depthBitmap.width / 2, depthBitmap.height / 2, true)




传感器平滑：
使用低通滤波减少抖动（已实现）。


限制更新频率：
传感器更新限制为 16ms（已实现）。


离屏渲染：
使用 RenderTarget 预渲染：val renderTarget = RenderTarget.Builder()
    .texture(Texture.Builder().width(surfaceView.width).height(surfaceView.height).build(engine))
    .build(engine)
renderer.beginFrame(renderTarget, viewport)





5. 注意事项

深度图格式：
确保 API 返回单通道灰度图（8 位），尺寸与彩色图片一致。
使用 Bitmap.createScaledBitmap 调整尺寸。


API 错误处理：
检查响应状态码，处理超时或失败。


设备兼容性：
Filament 1.57.1 的 Vulkan 后端需要 Android 7.0+。
检查陀螺仪支持（已实现）。


内存管理：
释放 Filament 资源和 Bitmap 对象。


材质调试：
检查着色器语法，确保符合 Filament 1.57.1 的 GLSL 要求。
使用 Log.e 捕获 MaterialBuilder 错误。



6. 扩展功能

动态光影：
在片段着色器中添加光照：vec3 lightDir = normalize(vec3(1.0, 1.0, 1.0));
float lightIntensity = max(0.0, 1.0 - depth);
fragColor.rgb *= lightIntensity;




多层景深：
将深度图分层，创建多个四边形和材质实例。


用户交互：
添加重置角度按钮：binding.btnReset.setOnClickListener { smoothedPitch = 0f; smoothedRoll = 0f }





7. 参考资源

Filament 1.57.1 文档：https://google.github.io/filament/
Filament GitHub：https://github.com/google/filament
Android 传感器：https://developer.android.com/guide/topics/sensors/sensors_motion
OkHttp：https://square.github.io/okhttp/


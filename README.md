# Parallax - 基于深度图的视差效果应用

## 项目简介

Parallax 是一个基于 Android 平台的视差效果应用，仿照ios26的图片深度效果，利用深度图和设备传感器实现动态的 3D 视差效果。当用户倾斜设备时，图片会根据深度信息产生立体的视觉效果，模拟真实的景深感。

## 主要功能

- **深度图渲染**：使用彩色图片和对应的深度图创建 3D 视差效果
- **传感器交互**：通过设备的旋转向量传感器检测倾斜角度
- **实时渲染**：基于 Google Filament 引擎的高性能实时渲染
- **自适应布局**：根据图片比例动态调整显示区域
- **平滑动画**：传感器数据平滑处理，提供流畅的视觉体验

## 技术栈

### 核心技术
- **Android SDK**: 最低支持 API 26 (Android 8.0)
- **Kotlin**: 主要开发语言
- **Google Filament**: 高性能 3D 渲染引擎
- **OpenGL ES**: 底层图形渲染

### 主要依赖
```kotlin
// Filament 渲染引擎
implementation("com.google.android.filament:gltfio-android:1.57.1")
implementation("com.google.android.filament:filament-utils-android:1.57.1")
implementation("com.google.android.filament:filamat-android:1.57.1")

// Android 基础库
implementation("androidx.core:core-ktx")
implementation("androidx.appcompat:appcompat")
implementation("com.google.android.material:material")
```

## 项目结构

```
app/src/main/java/com/youdao/parallax/
├── MainActivity.kt          # 主活动，负责UI交互和图片加载
├── DepthRenderer.kt         # 核心渲染器，处理3D渲染和传感器数据
└── databinding/            # 视图绑定相关文件

app/src/main/res/
├── layout/
│   └── activity_main.xml   # 主界面布局
└── values/                 # 资源文件

app/src/main/assets/
├── yadianuniversity.jpg     # 示例彩色图片
└── yadianuniversity_deps.png # 对应的深度图
```

## 核心实现原理

### 1. 深度图视差算法

应用使用自定义的 GLSL 着色器实现视差效果：

```glsl
void material(inout MaterialInputs material) {
    vec2 uv = getUV0();
    uv.y = 1.0 - uv.y; // 修正Y坐标翻转
    
    float depth = texture(materialParams_depthTexture, uv).r;
    vec2 offset = depth * materialParams.angles * materialParams.scaleFactor;
    vec2 newTexCoord = uv + offset;
    newTexCoord = clamp(newTexCoord, vec2(0.0), vec2(1.0));
    
    vec4 color = texture(materialParams_colorTexture, newTexCoord);
    material.baseColor = color;
}
```

### 2. 传感器数据处理

- 使用 `TYPE_ROTATION_VECTOR` 传感器获取设备姿态
- 应用低通滤波器平滑传感器数据，减少抖动
- 将旋转角度映射为纹理坐标偏移

### 3. 渲染管线优化

- 使用正交投影确保图片不变形
- ACES 色调映射提供专业级色彩表现
- 降低曝光度优化视觉效果
- 固定视口确保图片完全填充渲染区域

## 使用方法

### 环境要求
- Android 8.0 (API 26) 或更高版本
- 支持旋转向量传感器的设备
- OpenGL ES 3.0 支持

### 安装步骤

1. 克隆项目到本地
```bash
git clone [项目地址]
cd Parallax
```

2. 使用 Android Studio 打开项目

3. 同步 Gradle 依赖

4. 连接 Android 设备或启动模拟器

5. 运行应用

### 使用说明

1. 启动应用后，点击「加载测试图片」按钮
2. 应用会加载内置的示例图片和深度图
3. 倾斜设备观察视差效果
4. 图片会根据设备倾斜角度产生立体的景深效果

## 自定义图片

要使用自定义图片，需要准备：

1. **彩色图片**：标准的 RGB 图片
2. **深度图**：灰度图片，白色表示近景，黑色表示远景

将图片放入 `app/src/main/assets/` 目录，并修改 `MainActivity.kt` 中的文件路径。

## 性能优化

- 图片自动降采样减少内存占用
- 使用 RGB_565 格式降低内存使用
- 传感器数据限频避免过度渲染
- Filament 引擎提供硬件加速渲染

## 技术特点

### 渲染优化
- **材质系统**：自定义 GLSL 着色器实现视差效果
- **纹理管理**：高效的纹理创建和绑定
- **内存管理**：完善的资源释放机制

### 传感器处理
- **数据平滑**：低通滤波器减少传感器噪声
- **坐标映射**：精确的角度到偏移量转换
- **性能控制**：合理的更新频率控制

### 用户体验
- **自适应布局**：根据图片比例动态调整界面
- **流畅动画**：60fps 的平滑渲染
- **直观操作**：简单的倾斜交互方式

## 开发者说明

### 关键类说明

- **DepthRenderer**: 核心渲染器，负责 Filament 引擎管理、材质创建、传感器处理
- **MainActivity**: 主界面控制器，负责图片加载、UI 交互、布局管理

### 扩展建议

1. **多图片支持**：添加图片选择功能
2. **参数调节**：提供视差强度、平滑度等参数调节
3. **格式支持**：支持更多图片格式和深度图格式
4. **性能监控**：添加 FPS 显示和性能统计

## 许可证

本项目采用 MIT 许可证，详见 LICENSE 文件。

## 贡献

欢迎提交 Issue 和 Pull Request 来改进项目。

## 联系方式

如有问题或建议，请通过 GitHub Issues 联系。
package com.youdao.parallax

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.filament.Filament
import com.youdao.parallax.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var renderer: DepthRenderer
    private var surfaceView: SurfaceView? = null
    private var isMultiLayerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化 Filament native 库
        Filament.init()

        // 初始化 Filament 渲染视图
        setupFilament()

        // 加载测试图片按钮
        binding.btnLoadTestImage.setOnClickListener {
            loadTestImages()
        }
        
        // 切换渲染模式按钮
        binding.btnToggleRenderMode.setOnClickListener {
            toggleRenderMode()
        }
    }

    private fun setupFilament() {
        // 创建SurfaceView用于Filament渲染
        val surfaceView = SurfaceView(this)
        binding.filamentContainer.addView(surfaceView)
        renderer = DepthRenderer(this, surfaceView)
    }

    private fun loadTestImages() {
        try {
            // 从assets加载测试图片，使用采样降低分辨率
            val colorBitmap = assets.open("yadianuniversity.jpg").use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1 // 降低到1/4分辨率
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 // 使用更少内存的格式
                }
                BitmapFactory.decodeStream(inputStream, null, options)
            }
            
            val depthBitmap = assets.open("yadianuniversity_deps.png").use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1 // 降低到1/4分辨率
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeStream(inputStream, null, options)
            }
            
            if (colorBitmap != null && depthBitmap != null) {
                // 计算图片比例并调整预览框高度
                val imageAspectRatio = colorBitmap.width.toFloat() / colorBitmap.height.toFloat()
                val screenWidth = resources.displayMetrics.widthPixels - (32 * resources.displayMetrics.density).toInt() // 减去padding
                val newHeight = (screenWidth / imageAspectRatio).toInt()
                
                // 动态调整ImageView的高度
                val imageLayoutParams = binding.imagePreview.layoutParams
                imageLayoutParams.height = newHeight
                binding.imagePreview.layoutParams = imageLayoutParams
                binding.imagePreview.scaleType = android.widget.ImageView.ScaleType.FIT_XY
                
                // 动态调整Filament容器的高度，使其与图片比例一致
                val filamentLayoutParams = binding.filamentContainer.layoutParams as android.widget.LinearLayout.LayoutParams
                filamentLayoutParams.height = newHeight
                filamentLayoutParams.weight = 0f // 移除weight，使用固定高度
                binding.filamentContainer.layoutParams = filamentLayoutParams
                
                // 在ImageView中显示缩放后的图片
                binding.imagePreview.setImageBitmap(colorBitmap)
                
                // 加载纹理到Filament渲染器，并传递图片比例
                renderer.loadTextures(colorBitmap, depthBitmap, imageAspectRatio)
                
                Toast.makeText(this, "测试图片加载成功，倾斜设备查看景深效果", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "图片加载失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载图片时出错: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }
    
    /**
     * 切换渲染模式
     * 
     * 在单层渲染和多层渲染之间切换，并更新按钮文本
     */
    private fun toggleRenderMode() {
        if (!::renderer.isInitialized) {
            Toast.makeText(this, "请先加载图片", Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            isMultiLayerMode = !isMultiLayerMode
            val newMode = if (isMultiLayerMode) {
                RenderMode.MULTI_LAYER
            } else {
                RenderMode.SINGLE_LAYER
            }
            
            renderer.setRenderMode(newMode)
            
            // 更新按钮文本
            binding.btnToggleRenderMode.text = if (isMultiLayerMode) {
                "切换到单层渲染"
            } else {
                "切换到多层渲染"
            }
            
            val modeText = if (isMultiLayerMode) "多层渲染" else "单层渲染"
            Toast.makeText(this, "已切换到${modeText}模式", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Toast.makeText(this, "切换渲染模式失败: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (::renderer.isInitialized) {
            renderer.destroy()
        }
    }
}
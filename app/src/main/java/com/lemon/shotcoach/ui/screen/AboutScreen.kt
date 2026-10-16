package com.lemon.shotcoach.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lemon.shotcoach.ui.component.Divider
import com.lemon.shotcoach.ui.component.PageScaffold
import com.lemon.shotcoach.ui.component.SectionHeader
import com.lemon.shotcoach.ui.theme.ShotColors

/** 版本号（与 app/build.gradle.kts 的 versionName 保持一致） */
private const val APP_VERSION = "1.0.0"

/**
 * 关于页。
 *
 * 刻意把**权限清单**和**不联网**放在最显眼的位置 —— 对一个要拿摄像头的
 * App 来说，这两条比功能列表更能决定用户是否信任它。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    PageScaffold(title = "关于", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AI 构图教练",
                color = ShotColors.TextPrimary,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "版本 $APP_VERSION · MIT License",
                color = ShotColors.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(18.dp))

            // ── 权限与隐私 ────────────────────────────────────
            SectionHeader(title = "权限与隐私")
            InfoCard {
                InfoLine("申请权限", "仅「相机」一项")
                InfoLine("网络权限", "不申请")
                InfoLine("存储权限", "不申请（照片经系统相册接口写入）")
                InfoLine("画面去向", "全部在手机端分析，不上传、不落盘")
                InfoLine("人脸数据", "只做实时定位，不识别身份、不保存")
            }

            Spacer(Modifier.height(16.dp))

            // ── 它在端侧做了什么 ───────────────────────────────
            SectionHeader(title = "它是怎么工作的")
            InfoCard {
                InfoLine("1. 降采样", "每一帧缩到 224×224，像素少 160 倍")
                InfoLine("2. 快通道", "曝光直方图、水平仪、清晰度（每帧）")
                InfoLine("3. 慢通道", "显著性、地平线、人脸（每 2~3 帧）")
                InfoLine("4. 评分", "构图 40% · 水平 25% · 曝光 20% · 清晰 15%")
                InfoLine("5. 建议", "规则引擎 + 预写文案，零推理延迟")
            }
            Text(
                text = "整机不含任何神经网络模型文件，也不需要联网推理 —— " +
                    "所谓「实时 AI 建议」在这里就是经典 CV + 规则引擎，" +
                    "所以延迟能压在一帧之内。",
                color = ShotColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )

            Spacer(Modifier.height(16.dp))

            // ── 零素材 ────────────────────────────────────────
            SectionHeader(title = "零素材文件")
            InfoCard {
                InfoLine("滤镜", "由色彩参数实时烘焙成 3D LUT，可导出 .cube")
                InfoLine("边框", "Canvas 程序化绘制，随输出分辨率自适应")
                InfoLine("水印", "代码绘制文字，不含任何图片")
                InfoLine("图标/启动图", "全部矢量绘制，无 PNG 资产")
            }

            Spacer(Modifier.height(16.dp))

            SectionHeader(title = "技术栈")
            InfoCard {
                InfoLine("语言", "Kotlin 1.9")
                InfoLine("UI", "Jetpack Compose (Material 3)")
                InfoLine("相机", "CameraX 1.3（Preview / ImageAnalysis / ImageCapture）")
                InfoLine("视觉", "OpenCV 4（Haar、Hough、显著性、直方图、Laplacian）")
                InfoLine("持久化", "SharedPreferences（无数据库、无网络）")
            }

            Spacer(Modifier.height(16.dp))

            SectionHeader(title = "与同类商业产品的区别")
            InfoCard {
                InfoLine("付费墙", "无。所有功能、全部滤镜边框水印都可用")
                InfoLine("登录", "无。不收集手机号，没有账号体系")
                InfoLine("联网", "无。没有云端修图、没有素材下载")
                InfoLine("素材", "无。美术资产全部由代码生成")
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "本应用是一个教学性的开源实现，用于说明「端侧实时构图引导」的完整做法。" +
                    "如果不打算自己构建，可执行 ./gradlew assembleDebug。",
                color = ShotColors.TextDisabled,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ShotColors.Surface)
            .padding(14.dp),
    ) {
        content()
    }
}

@Composable
private fun InfoLine(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            text = key,
            color = ShotColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            color = ShotColors.TextPrimary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
    }
}

/** 供各页复用的文字按钮（避免 Material Button 的默认内边距破坏视觉） */
@Composable
internal fun TextAction(
    text: String,
    onClick: () -> Unit,
    accent: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = if (accent) ShotColors.Accent else ShotColors.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ShotColors.Surface)
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}

/** 供各页复用的小提示行 */
@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = ShotColors.TextDisabled,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** 供各页复用的分隔（避免直接引用 Divider 导致 import 冗余） */
@Composable
internal fun SectionDivider() {
    Divider(modifier = Modifier.padding(top = 14.dp))
}

/** 供各页复用的占位色 */
internal val SubtleBorder: Color = Color(0x22FFFFFF)

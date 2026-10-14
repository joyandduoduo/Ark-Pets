# 角色陪伴定制版

以角色为单位启动，将同一角色的皮肤、基建与战斗模型放在一起。只获取 `docs/companion-roster.json` 指定的角色，不需要下载完整模型库。

## 使用

打开独立包中的 `打开角色陪伴.cmd`，选择角色后点击「请他来陪伴」。同一角色正在运行时，再次点击只应用设置；外观与基建／战斗姿态可直接切换，保留角色的桌面物理位置。

- 关闭管理窗口会保留已启动角色；「让他休息」退出当前角色，其他角色继续陪伴。
- 基建模式适合日常行走、坐下、休息；实际动作取决于该模型已有的动画。
- 战斗模式使用其原有待机。没有行走动作的模型保持原地，不伪造滑行。
- 「技能动作」将技能起手、单轮循环、收招串起来，然后回到待机。倒下与死亡不加入自动动作。
- 普通话陪伴语音默认每10～20分钟随机播放，一句结束后重新计时。一轮内不重复，多只角色通过本地音频锁错开播放。可关闭自动语音、调整间隔和音量，或手动播放一句。
- 缺少皮肤、基建动作或中文普通话配音时明确显示缺项，不用其他角色的素材代替。

## 构建与素材

需要 Java 17 JDK 和 Python 3。通过 Gradle Wrapper 构建：

```powershell
python docs/scripts/fetch_companions.py --output companion-data
python docs/scripts/package_companion.py --java-home C:/path/to/jdk-17 --output C:/path/to/companion
```

将 `companion-data` 放在输出目录。素材目录包含来源、文件大小、SHA-256 与获取缺项。没有将游戏素材纳入源代码仓库；素材权利不由 GPL 授予。

原有 ArkPets 启动器仍保留；开发时可运行 `gradlew desktop:companionRun`。

## 设计取舍

使用现有 JavaFX + libGDX + Spine 3.8，保留原有窗口碰撞、透明像素点击与基建动作。新增独立角色管理入口，不强制重做原启动器。已有 ArkPets-Web 提供网页渲染，但不能直接保留 Windows 窗口平台能力；外部覆盖层可以少改源码，但角色换装与资源释放需要绕过原运行时，因此此处采用 Fork 内实现。

界面沿用原 Main.css 的蓝白色与 Source Han Sans CN。比较过原生列表、分组卡片、舞台预览三种布局，本轮采用原生角色列表，优先验证启动、换装、动作与语音。如果后续目标改为跨平台或网页嵌入，应重新评估渲染架构。

## 验证边界

编译、资源可解码、程序内渲染、计时策略和真实使用感受分别验收。资源检查不会证明每个动作都适合长期陪伴；尤其大范围技能特效仍需要逐角色目视挑选。

原始来源：

- https://github.com/isHarryh/Ark-Pets
- https://prts.wiki/
- https://github.com/Kengxxiao/ArknightsGameData
- https://libgdx.com/wiki/audio/audio
- https://github.com/fuyufjh/ArkPets-Web

---
name: SHiNe MUSIC
description: 熟悉、沉浸、克制的家庭音乐界面
colors:
  shine-coral: "#ED5564"
  night: "#111113"
  night-raised: "#1B1B1F"
  night-soft: "#25252B"
  cloud: "#F8F7FA"
  ink: "#202124"
  muted: "#6F6F78"
  success: "#2E7D5B"
  danger: "#BA1A1A"
typography:
  headline:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "1.75rem"
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 600
    lineHeight: 1.4
  body:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Inter, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 600
    lineHeight: 1.3
rounded:
  sm: "8px"
  md: "12px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.shine-coral}"
    textColor: "#FFFFFF"
    typography: "{typography.title}"
    rounded: "{rounded.pill}"
    padding: "10px 18px"
  navigation-active:
    backgroundColor: "{colors.night-soft}"
    textColor: "#FFFFFF"
    typography: "{typography.title}"
    rounded: "{rounded.md}"
    padding: "10px 12px"
---

# Design System: SHiNe MUSIC

## Overview

**Creative North Star: "The Shared Listening Room"**

界面像一间随时可进入的家庭听音室：音乐封面与播放状态提供氛围，导航、列表和控制保持熟悉可靠。手机端强调单手触控和沉浸播放，桌面端以左侧导航、中央内容、可选右侧队列和底部播放器组织同一套信息。

系统拒绝通用管理后台感、玻璃拟态与装饰性动效。结构应在不同宽度下重排，而不是缩放或复制。

**Key Characteristics:**

- 封面与当前播放主导视觉层级。
- 深浅主题共享一套语义色角色。
- 桌面高密度、手机触控友好。
- 状态变化即时、克制且可撤销。

## Colors

珊瑚红只承担主操作、选中状态和播放进度；其余界面由中性表面建立层级。

### Primary

- **SHiNe Coral**：品牌锚点，用于主按钮、活动导航和播放进度。

### Neutral

- **Listening Night**：深色主题背景。
- **Raised Night**：侧栏、播放器和浮层表面。
- **Cloud**：浅色主题背景。
- **Ink**：浅色主题主文本。
- **Muted**：次要文本，仅在满足 AA 对比度时使用。

**The One Voice Rule.** 珊瑚红在单屏内保持稀缺；若所有元素都强调，就没有元素真正重要。

## Typography

**Display Font:** Inter（回退到系统无衬线字体）
**Body Font:** Inter（回退到系统无衬线字体）

**Character:** 单一无衬线字体让中英文标签、曲目元数据和控制保持稳定，不用展示字体干扰音乐内容。

### Hierarchy

- **Headline**：页面标题与专辑标题，最多两行。
- **Title**：曲名、导航和主要操作。
- **Body**：描述与元数据；长文本限制在 65–75ch。
- **Label**：时间、音质、来源和辅助状态，不使用全大写字距装饰。

## Elevation

系统以色调分层为主。阴影只用于菜单、对话框和拖拽中的队列项，静止列表和内容容器不使用宽而模糊的装饰阴影。

**The Flat-by-Default Rule.** 静止表面通过背景明度区分；只有临时浮在内容之上的元素获得阴影。

## Components

### Buttons

- **Shape:** 主操作使用完整胶囊形；工具按钮使用 8px 圆角。
- **Primary:** 珊瑚红背景与白色文字，最小高度 44px。
- **Hover / Focus:** 亮度变化不超过一个色阶；键盘焦点使用清晰的双层轮廓。
- **Secondary / Ghost:** 保持中性表面，不与主操作竞争。

### Chips

- **Style:** 用于来源、筛选和房间状态；未选中时为中性表面，选中时使用低饱和珊瑚色容器。
- **State:** 文字或图标必须同时表达选中状态，不能只依赖颜色。

### Cards / Containers

- **Corner Style:** 内容容器最多 12px 圆角。
- **Background:** 使用相邻中性色调形成层级。
- **Shadow Strategy:** 遵循扁平优先规则。
- **Internal Padding:** 手机 12–16px，桌面 16–24px。

### Inputs / Fields

- **Style:** 实色表面、8px 圆角、44px 最小高度。
- **Focus:** 2px 珊瑚色焦点环，并保留浏览器语义。
- **Error / Disabled:** 同时显示文本说明；禁用态仍需可读。

### Navigation

桌面使用左侧导航，手机使用底部导航。活动项使用实色容器和图标/字重双重提示；底部播放器始终避让导航与安全区。

### Player

迷你播放器、桌面底栏和全屏播放页共享同一个播放状态。播放/暂停是最高优先级控件，同步房间状态紧邻设备与队列入口显示。

## Do's and Don'ts

### Do:

- **Do** 在所有视口保持当前播放可见并可键盘操作。
- **Do** 使用 44px 最小触控目标和可见焦点。
- **Do** 让桌面列表承载排序、多选和队列操作，让手机使用渐进式菜单。
- **Do** 为加载、空状态、Sendspin 断线重连和同步误差提供明确说明。

### Don't:

- **Don't** 做成通用管理后台式的表格堆叠。
- **Don't** 照搬 Spotify 品牌色、字形或装饰。
- **Don't** 使用玻璃拟态、霓虹渐变、渐变文字、超大圆角卡片或装饰性页面入场动画。
- **Don't** 把手机页面简单拉宽或维护两套互相漂移的业务界面。
- **Don't** 同时给容器添加 1px 边框和大于 16px 模糊的宽阴影。

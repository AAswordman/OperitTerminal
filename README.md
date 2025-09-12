# Assistance BuzyBox

一个在Android上运行Ubuntu环境的终端应用。

## 项目描述

这是一个Android终端应用，集成了Ubuntu环境，允许用户在Android设备上运行Linux命令和工具。

## 构建配置

### 密钥配置

为了构建发布版本，需要配置签名密钥。请按照以下步骤操作：

1. 在项目根目录创建 `keystore.properties` 文件
2. 添加以下配置项（请替换为你的实际值）：

```properties
RELEASE_KEY_ALIAS=你的密钥别名
RELEASE_KEY_PASSWORD=你的密钥密码
RELEASE_STORE_FILE=你的密钥库文件路径
RELEASE_STORE_PASSWORD=你的密钥库密码
```

**注意：**
- `keystore.properties` 文件包含敏感信息，不应提交到版本控制系统
- 该文件已被添加到 `.gitignore` 中，确保不会意外提交
- 如果没有配置密钥，debug版本仍可正常构建和运行

### Android SDK配置

项目使用 `local.properties` 文件配置Android SDK路径：
- 该文件由Android Studio自动生成
- 包含本地SDK路径配置
- 不应提交到版本控制系统

## 许可证

遵循 GPLv3 协议。

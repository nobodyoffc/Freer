# SettingsManager 使用说明

## 概述

`SettingsManager` 是一个单例类，用于管理不同 FID 的 `Settings` 对象。它提供了集中化的方式来访问每个 FID 的 Settings，并自动处理本地存储和 Configure 的同步。

## 主要功能

1. **获取或创建 Settings**: 根据 KeyInfo 获取现有的 Settings，如果不存在则创建新的
2. **本地存储管理**: 使用 SharedPreferences 持久化存储 Settings
3. **Configure 同步**: 自动将 KeyInfo 添加到 Configure 的 mainCidInfoMap 中
4. **删除功能**: 删除 Settings 并从 Configure 中移除对应的 KeyInfo

## 使用方法

### 1. 获取 SettingsManager 实例

```java
SettingsManager settingManager = SettingsManager.getInstance();
```

### 2. 获取或创建 Settings

```java
// 假设你有一个 KeyInfo 对象
KeyInfo keyInfo = new KeyInfo();
keyInfo.setId("your_fid_here");
keyInfo.setPrikeyCipher("encrypted_private_key");

// 获取或创建 Setting
Settings setting = settingManager.getOrCreateSettings(context, keyInfo);
```

### 3. 检查 Settings 是否存在

```java
boolean exists = settingManager.hasSettings(context, "your_fid_here");
```

### 4. 删除 Settings

```java
settingManager.removeSettings(context, "your_fid_here");
```

### 5. 获取所有 FID

```java
Set<String> allFids = settingManager.getAllFids(context);
```

### 6. 清除所有 Settings

```java
settingManager.clearAllSettings(context);
```

## 工作流程

### 创建新 Settings 的流程：

1. 调用 `getOrCreateSettings(context, keyInfo)`
2. 首先尝试从本地存储加载现有的 Settings
3. 如果找到，直接返回
4. 如果没找到，创建新的 Settings 对象
5. 将新 Settings 保存到本地存储
6. 将 KeyInfo 添加到 Configure 的 mainCidInfoMap 中
7. 返回新创建的 Settings

### 删除 Settings 的流程：

1. 调用 `removeSettings(context, fid)`
2. 从本地存储中删除对应的 Settings
3. 从 Configure 的 mainCidInfoMap 中移除对应的 KeyInfo
4. 保存更新后的 Configure

## 注意事项

1. **Context 要求**: 所有方法都需要有效的 Context 参数
2. **KeyInfo 验证**: KeyInfo 和其 ID 不能为 null
3. **ConfigureManager 依赖**: 需要确保 ConfigureManager 已经正确初始化
4. **异常处理**: 建议在使用时添加适当的异常处理

## 示例代码

参考 `SettingsManagerExample.java` 文件中的完整使用示例。

## 存储结构

Settings 使用以下 SharedPreferences 结构存储：

- **SharedPreferences 名称**: `fc_settings_prefs`
- **键名**: `settings_map`
- **值类型**: JSON 字符串，包含 FID 到 Settings 对象的映射

## 与 ConfigureManager 的集成

SettingsManager 与 ConfigureManager 紧密集成：

- 使用 ConfigureManager 获取当前的 Configure 对象
- 使用 ConfigureManager 获取 symkey
- 自动同步 KeyInfo 到 Configure 的 mainCidInfoMap
- 使用 Configure 的静态 Context 进行存储操作 
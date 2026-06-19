# Service 架构重构计划文档

## 一、项目概述

### 1.1 重构目标
- 将 `Service` 类中的 `List<String> types` 改为 `ServiceType type`，明确服务类型
- 添加 `List<String> apiGroups` 字段，声明服务提供的 API 组件
- 重构客户端管理架构，支持按 API 组件管理客户端
- 支持从不同服务提供者获取不同组件
- 服务端的重构不在本项目中考虑
- 不需要向后兼容

### 1.2 FAPI 组件定义
- **BASE**: 基础组件（其他组件的前提，当前必需）
- **DISK**: 磁盘组件（未实现）
- **MAP**: 地图组件（未实现）
- **TALK**: 聊天组件（未实现）
- **ROAD**: 路由组件（未实现）
- **DOCK**: 码头组件（未实现）
- **BANK**: 银行组件（未实现）

### 1.3 当前需求
- 仅使用 **BASE** 组件
- BASE 组件需要 **1个客户端**
- BASE 是其他组件的前提条件
- 支持从不同服务获取不同组件（为未来扩展做准备）

---

## 二、架构设计

### 2.1 核心数据结构变更

#### Service 类
```java
// 旧结构
protected List<String> types;

// 新结构
protected ServiceType type;              // 服务类型（FAPI, APIP, DISK等）
protected List<String> apiGroups;        // API组件列表（BASE, MAP等）
```

#### 组件配置
```java
// 在 FreerApplication 中定义
public static final Map<String, ApiComponentConfig> API_COMPONENT_CONFIG = new HashMap<>();

// BASE 组件配置
API_COMPONENT_CONFIG.put("BASE", new ApiComponentConfig("BASE", 1, 10, true));
```

### 2.2 客户端管理架构

#### 当前架构
- 按 `ServiceType` 分组（FAPI）
- 每个组管理多个客户端

#### 新架构
- 保持按 `ServiceType` 分组
- 内部按 `apiGroups` 组件分组管理客户端
- 支持为每个组件配置不同的客户端数量
- 支持从不同服务提供者获取不同组件

---

## 三、详细实施计划

### 阶段 1: 核心数据结构修改

#### 1.1 修改 Service.java
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/data/feipData/Service.java`

**修改内容**:
- [ ] 将 `protected List<String> types;` 改为 `protected ServiceType type;`
- [ ] 添加 `protected List<String> apiGroups;` 字段
- [ ] 删除 `getTypes()` 和 `setTypes()` 方法
- [ ] 添加 `getType()` 和 `setType(ServiceType type)` 方法
- [ ] 添加 `getApiGroups()` 和 `setApiGroups(List<String> apiGroups)` 方法
- [ ] 更新 `ServiceToMask()` 相关逻辑（如果存在）

**注意事项**:
- 彻底移除 `types` 相关代码，不保留向后兼容

#### 1.2 修改 ServiceOpData.java
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/data/feipData/ServiceOpData.java`

**修改内容**:
- [ ] 将 `private String[] types;` 改为 `private String type;`
- [ ] 添加 `private String[] apiGroups;` 字段
- [ ] 更新 `getTypes()` 和 `setTypes()` 为 `getType()` 和 `setType()`
- [ ] 添加 `getApiGroups()` 和 `setApiGroups()` 方法
- [ ] 更新 `OP_FIELDS` 映射，添加 `FieldNames.TYPE` 和 `FieldNames.API_GROUPS` 到 PUBLISH 和 UPDATE 操作：
  ```java
  OP_FIELDS.put(Op.PUBLISH.toLowerCase(), new String[]{
      FieldNames.STD_NAME, FieldNames.LOCAL_NAMES, Values.DESC, 
      FieldNames.TYPE, FieldNames.API_GROUPS,  // 新增
      FieldNames.VER, FieldNames.URLS, FieldNames.WAITERS, 
      FieldNames.PROTOCOLS, FieldNames.CODES, FieldNames.SERVICES, FieldNames.PARAMS
  });
  OP_FIELDS.put(Op.UPDATE.toLowerCase(), new String[]{
      FieldNames.SID, FieldNames.STD_NAME, FieldNames.LOCAL_NAMES, Values.DESC, 
      FieldNames.TYPE, FieldNames.API_GROUPS,  // 新增
      FieldNames.VER, FieldNames.URLS, FieldNames.WAITERS, 
      FieldNames.PROTOCOLS, FieldNames.CODES, FieldNames.SERVICES, FieldNames.PARAMS
  });
  ```
- [ ] 更新 `makePublish()` 方法签名，添加 `type` 和 `apiGroups` 参数
- [ ] 更新 `makeUpdate()` 方法签名，添加 `type` 和 `apiGroups` 参数

**注意事项**:
- 链上操作使用数组格式，但 Service 类使用 List
- type 在 OpData 中是 String，在 Service 中是 ServiceType 枚举

#### 1.3 修改 ServiceMask.java
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/data/feipData/ServiceMask.java`

**修改内容**:
- [ ] 将 `protected List<String> types;` 改为 `protected ServiceType type;`
- [ ] 添加 `protected List<String> apiGroups;` 字段
- [ ] 更新 `getTypes()` 和 `setTypes()` 方法
- [ ] 添加 `getApiGroups()` 和 `setApiGroups()` 方法
- [ ] 更新 `ServiceToMask()` 方法中的类型转换逻辑

#### 1.4 修改 FieldNames.java
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/constants/FieldNames.java`

**修改内容**:
- [ ] 添加常量：`public static final String API_GROUPS = "apiGroups";`
- [ ] 添加常量：`public static final String TYPE = "type";`（如果不存在）
- [ ] 保留 `TYPES` 常量（可能被其他代码使用，但 Service 相关代码不再使用）

---

### 阶段 2: FapiClient 服务发现逻辑修改

#### 2.1 修改 parseFapiServicesFromPong()
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/fapi/client/FapiClient.java`

**修改内容**:
- [ ] 将 `containsFapiType()` 调用改为检查 `type` 字段
- [ ] 解析 `type` 字段（优先），兼容旧格式（如果需要）
- [ ] 解析 `apiGroups` 字段并设置到 Service 对象
- [ ] 更新 Service 对象构建逻辑：
  ```java
  service.setType(ServiceType.fromString(stringVal(svcMap.get(FieldNames.TYPE))));
  // 解析 apiGroups
  Object apiGroupsObj = svcMap.get(FieldNames.API_GROUPS);
  if (apiGroupsObj instanceof List<?> list) {
      service.setApiGroups((List<String>) list);
  }
  ```

#### 2.2 修改 containsFapiType() 方法
**修改内容**:
- [ ] 重命名为 `isFapiType(Service service)`
- [ ] 改为检查 `service.getType() == Service.ServiceType.FAPI`
- [ ] 删除旧的 `containsFapiType(Object typesObj)` 方法

#### 2.3 修改 findServiceByType() 方法
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/fapi/client/FapiClient.java`

**修改内容**:
- [ ] 改为直接比较 `service.getType()` 与 `targetType`
- [ ] 移除对 `types` 列表的遍历逻辑

#### 2.4 添加 hasApiGroups() 方法
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/fapi/client/FapiClient.java`

**新增方法**:
```java
/**
 * 检查服务是否包含所有必需的 API 组件
 * @param service 服务对象
 * @param requiredComponents 必需的组件列表，如 ["BASE", "MAP"]
 * @return true 如果服务包含所有必需组件
 */
public static boolean hasApiGroups(Service service, List<String> requiredComponents) {
    if (service == null || requiredComponents == null || requiredComponents.isEmpty()) {
        return false;
    }
    List<String> serviceGroups = service.getApiGroups();
    if (serviceGroups == null || serviceGroups.isEmpty()) {
        return false;
    }
    // 转换为大写进行不区分大小写的比较
    Set<String> serviceGroupsUpper = serviceGroups.stream()
        .map(String::toUpperCase)
        .collect(Collectors.toSet());
    Set<String> requiredUpper = requiredComponents.stream()
        .map(String::toUpperCase)
        .collect(Collectors.toSet());
    return serviceGroupsUpper.containsAll(requiredUpper);
}
```

#### 2.5 更新 bootstrap 方法
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/fapi/client/FapiClient.java`

**修改内容**:
- [ ] 在 `bootstrapWithResult()` 中添加 `requiredComponents` 参数
- [ ] 检查 `service.getType() == targetType`
- [ ] 检查 `hasApiGroups(service, requiredComponents)`
- [ ] 如果组件检查失败，记录日志并继续查找下一个服务

---

### 阶段 3: API 组件配置系统

#### 3.1 创建 ApiComponentConfig 类
**新文件**: `app/src/main/java/com/fc/freer/config/ApiComponentConfig.java`

**类定义**:
```java
package com.fc.freer.config;

/**
 * API 组件配置类
 * 定义每个 API 组件需要的客户端数量和优先级
 */
public class ApiComponentConfig {
    private final String componentName;      // 组件名称，如 "BASE", "MAP"
    private final int requiredClientCount;   // 需要的客户端数量
    private final int priority;              // 优先级（数字越大优先级越高）
    private final boolean isCritical;        // 是否为关键组件（必须存在）
    
    public ApiComponentConfig(String componentName, int requiredClientCount, 
                            int priority, boolean isCritical) {
        this.componentName = componentName;
        this.requiredClientCount = requiredClientCount;
        this.priority = priority;
        this.isCritical = isCritical;
    }
    
    // Getters
    public String getComponentName() { return componentName; }
    public int getRequiredClientCount() { return requiredClientCount; }
    public int getPriority() { return priority; }
    public boolean isCritical() { return isCritical; }
}
```

#### 3.2 修改 FreerApplication.java
**文件**: `app/src/main/java/com/fc/freer/FreerApplication.java`

**修改内容**:
- [ ] 导入 `ApiComponentConfig`
- [ ] 添加组件配置映射：
  ```java
  public static final Map<String, ApiComponentConfig> API_COMPONENT_CONFIG = new HashMap<>();
  
  static {
      // BASE 组件：关键组件，需要 1 个客户端（当前需求）
      API_COMPONENT_CONFIG.put("BASE", 
          new ApiComponentConfig("BASE", 1, 10, true));
      
      // 其他组件配置（为未来扩展准备，当前未使用）
      // API_COMPONENT_CONFIG.put("MAP", new ApiComponentConfig("MAP", 1, 8, false));
      // API_COMPONENT_CONFIG.put("DISK", new ApiComponentConfig("DISK", 1, 7, false));
      // ...
  }
  ```
- [ ] 添加获取组件配置的方法：
  ```java
  public static Map<String, ApiComponentConfig> getApiComponentConfig() {
      return new HashMap<>(API_COMPONENT_CONFIG);
  }
  
  public static List<String> getRequiredComponents() {
      return API_COMPONENT_CONFIG.values().stream()
          .filter(ApiComponentConfig::isCritical)
          .map(ApiComponentConfig::getComponentName)
          .collect(Collectors.toList());
  }
  ```
- [ ] 移除或更新 `serviceNumberMap`（如果不再需要）

---

### 阶段 4: ClientGroup 重构

#### 4.1 修改 ClientGroup.java
**文件**: `app/src/main/java/com/fc/freer/initiate/ClientGroup.java`

**修改内容**:

1. **添加组件管理字段**:
   ```java
   // 按 API 组件分组管理客户端
   // Map<componentName, List<accountId>>
   private transient Map<String, List<String>> componentAccountMap;
   
   // 组件配置（从 FreerApplication 获取）
   private transient Map<String, ApiComponentConfig> componentConfig;
   ```

2. **添加组件管理方法**:
   ```java
   /**
    * 为指定组件获取客户端列表
    */
   public List<Object> getClientsForComponent(String componentName) {
       if (componentAccountMap == null) return Collections.emptyList();
       List<String> accountIds = componentAccountMap.get(componentName);
       if (accountIds == null) return Collections.emptyList();
       
       return accountIds.stream()
           .map(accountId -> getClientMap().get(accountId))
           .filter(Objects::nonNull)
           .filter(this::isClientValid)
           .collect(Collectors.toList());
   }
   
   /**
    * 为组件添加客户端
    */
   public void addClientForComponent(String componentName, String accountId, Object client) {
       if (componentAccountMap == null) {
           componentAccountMap = new HashMap<>();
       }
       componentAccountMap.computeIfAbsent(componentName, k -> new ArrayList<>())
           .add(accountId);
       addClient(accountId, client);
   }
   
   /**
    * 检查组件是否有足够的客户端
    */
   public boolean hasEnoughClientsForComponent(String componentName) {
       if (componentConfig == null) {
           loadComponentConfig();
       }
       ApiComponentConfig config = componentConfig.get(componentName);
       if (config == null) return false;
       
       int currentCount = getClientsForComponent(componentName).size();
       return currentCount >= config.getRequiredClientCount();
   }
   
   /**
    * 加载组件配置
    */
   private void loadComponentConfig() {
       componentConfig = FreerApplication.getApiComponentConfig();
   }
   ```

3. **修改 connectAllClients() 方法**:
   ```java
   public void connectAllClients(Context context, byte[] symKey) {
       Configure configure = ConfigureManager.getInstance().getConfigure();
       if (configure == null) {
           TimberLogger.e(TAG, "Configure is null, cannot connect clients");
           return;
       }
       
       // 加载组件配置
       loadComponentConfig();
       
       // 获取必需的组件列表
       List<String> requiredComponents = FreerApplication.getRequiredComponents();
       
       // 为每个组件创建客户端
       for (String componentName : requiredComponents) {
           ApiComponentConfig config = componentConfig.get(componentName);
           if (config == null) continue;
           
           // 检查当前组件已有客户端数量
           int currentCount = getClientsForComponent(componentName).size();
           int needed = config.getRequiredClientCount() - currentCount;
           
           if (needed <= 0) {
               TimberLogger.d(TAG, "Component %s has enough clients", componentName);
               continue;
           }
           
           // 查找提供该组件的服务并创建客户端
           createClientsForComponent(context, componentName, needed, configure, symKey);
       }
   }
   
   /**
    * 为指定组件创建客户端
    */
   private void createClientsForComponent(Context context, String componentName, 
                                        int count, Configure configure, byte[] symKey) {
       // 1. 从已保存的 ApiProvider 中查找提供该组件的服务
       // 2. 如果找不到，使用 bootstrap 发现新服务
       // 3. 创建客户端直到达到所需数量
       
       List<String> requiredComponents = Collections.singletonList(componentName);
       
       // 尝试从现有 provider 创建
       for (ApiProvider provider : configure.getApiProviderMap().values()) {
           if (count <= 0) break;
           
           if (provider.getType() == getGroupType() && 
               FapiClient.hasApiGroups(provider, requiredComponents)) {
               // 创建客户端
               Object client = createClientFromProvider(context, provider, configure, symKey);
               if (client != null) {
                   String accountId = createOrGetAccountId(provider, configure);
                   addClientForComponent(componentName, accountId, client);
                   count--;
               }
           }
       }
       
       // 如果还需要更多客户端，使用 bootstrap
       if (count > 0) {
           bootstrapAndCreateClients(context, componentName, count, configure, symKey);
       }
   }
   ```

4. **更新 initTransientFields()**:
   ```java
   @Override
   protected void initTransientFields() {
       super.initTransientFields();
       if (apiAccountMap == null) {
           apiAccountMap = new HashMap<>();
       }
       if (componentAccountMap == null) {
           componentAccountMap = new HashMap<>();
       }
       loadComponentConfig();
   }
   ```

#### 4.2 修改 createFapiClient() 方法
**修改内容**:
- [ ] 更新 bootstrap 调用，传入必需的组件列表：
  ```java
  List<String> requiredComponents = FreerApplication.getRequiredComponents();
  FapiClient.BootstrapResult result = FapiClient.bootstrapWithResult(
      fudpNode, 
      Service.ServiceType.FAPI, 
      setting.getSettingMap(),
      requiredComponents,
      DEFAULT_HELLO_TIMEOUT_MS, 
      DEFAULT_PING_TIMEOUT_MS
  );
  ```

---

### 阶段 5: ApiProvider 更新

#### 5.1 修改 ApiProvider.java
**文件**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/fapi/client/ApiProvider.java`

**修改内容**:
- [ ] 更新 `fromService()` 方法：
  ```java
  // 旧代码
  if(service.getTypes()!=null) {
      this.setTypes(service.getTypes());
  }
  
  // 新代码
  this.setType(service.getType());
  this.setApiGroups(service.getApiGroups());
  ```

- [ ] 更新 `fromService(Service, ServiceType)` 方法：
  ```java
  // 旧代码
  if(type != null && (apiProvider.getTypes() == null || apiProvider.getTypes().isEmpty())) {
      apiProvider.setTypes(List.of(type.name()));
  }
  
  // 新代码
  if (type != null && apiProvider.getType() == null) {
      apiProvider.setType(type);
  }
  ```

- [ ] 更新字段名称映射（`getFieldNamesEn()` 和 `getFieldNamesZh()`）：
  - 将 "types" 改为 "type"
  - 添加 "apiGroups"

---

### 阶段 5.5: 应用层代码更新

#### 5.5.1 修改 ApiServicesActivity.java
**文件**: `app/src/main/java/com/fc/freer/home/ApiServicesActivity.java`

**修改内容**:
- [ ] 第170行：将 `apiProvider.getTypes() != null` 改为 `apiProvider.getType() != null`

#### 5.5.2 修改 ChangeApiServiceActivity.java
**文件**: `app/src/main/java/com/fc/freer/home/ChangeApiServiceActivity.java`

**修改内容**:
- [ ] 第441行：将 `provider.getTypes().contains(serviceType.name())` 改为 `provider.getType() == serviceType`

---

## 四、测试计划

### 4.1 单元测试
- [ ] Service 类序列化/反序列化测试
- [ ] FapiClient.hasApiGroups() 方法测试
- [ ] Service 类型检查测试
- [ ] ApiComponentConfig 配置测试

### 4.2 集成测试
- [ ] 服务发现测试（PONG 响应解析）
- [ ] 组件检查测试
- [ ] 客户端创建测试
- [ ] 多组件支持测试

### 4.3 功能测试
- [ ] BASE 组件客户端创建
- [ ] 从不同服务获取不同组件
- [ ] 组件配置验证
- [ ] 客户端连接状态检查

---

## 五、注意事项

### 5.1 兼容性
- **不保留向后兼容**：彻底移除 `types` 相关代码
- 确保所有使用 `Service.types` 的地方都已更新

### 5.2 数据迁移
- 如果已有持久化的 Service 数据，需要迁移脚本
- 检查数据库或配置文件中的旧格式数据

### 5.3 组件命名
- 组件名称使用大写：`"BASE"`, `"MAP"` 等
- 比较时使用大小写不敏感的方式

### 5.4 BASE 组件前提
- 确保 BASE 组件是其他组件的前提
- 在创建其他组件客户端前，先确保 BASE 组件可用

### 5.5 未来扩展
- 架构设计支持未来添加更多组件
- 组件配置可以在 `FreerApplication` 中轻松修改
- 支持为不同组件配置不同的客户端数量

---

## 六、实施顺序

1. **阶段 1**：核心数据结构修改（Service, ServiceOpData, ServiceMask, FieldNames）
2. **阶段 2**：FapiClient 服务发现逻辑修改
3. **阶段 3**：API 组件配置系统（创建 ApiComponentConfig，修改 FreerApplication）
4. **阶段 4**：ClientGroup 重构
5. **阶段 5**：ApiProvider 更新
6. **阶段 5.5**：应用层代码更新（ApiServicesActivity, ChangeApiServiceActivity）
7. **测试验证**：各阶段完成后进行测试

---

## 七、风险评估

### 7.1 高风险项
- Service 类结构变更可能影响序列化/反序列化
- 服务发现逻辑变更可能影响客户端连接

### 7.2 缓解措施
- 充分测试序列化/反序列化
- 保留详细的日志记录
- 分阶段实施，每阶段完成后验证

---

## 八、后续优化

### 8.1 交叉验证机制
- 当 BASE 组件配置多个客户端时，实现数据交叉验证
- 比较不同客户端返回的结果，检测异常

### 8.2 组件健康检查
- 定期检查各组件的客户端连接状态
- 自动替换失效的客户端

### 8.3 动态组件配置
- 支持运行时动态添加/移除组件配置
- 支持从配置文件加载组件配置

---

## 九、文档更新

- [ ] 更新 API 文档，说明新的 Service 结构
- [ ] 更新开发者指南，说明组件配置方法
- [ ] 更新架构文档，说明新的客户端管理方式

---

## 十、完成标准

- [ ] 所有代码修改完成
- [ ] 单元测试通过
- [ ] 集成测试通过
- [ ] 功能测试通过
- [ ] 代码审查通过
- [ ] 文档更新完成

---

**文档版本**: v1.0  
**创建日期**: 2024  
**最后更新**: 2024


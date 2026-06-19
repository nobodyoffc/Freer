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
    public String getComponentName() { 
        return componentName; 
    }
    
    public int getRequiredClientCount() { 
        return requiredClientCount; 
    }
    
    public int getPriority() { 
        return priority; 
    }
    
    public boolean isCritical() { 
        return isCritical; 
    }
}


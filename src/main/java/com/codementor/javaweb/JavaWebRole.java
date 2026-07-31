package com.codementor.javaweb;

/** 当前 Java 类型在常见 JavaWeb 分层中的角色。 */
public enum JavaWebRole {
    CONTROLLER("Controller"),
    SERVICE("Service"),
    REPOSITORY("Repository / Mapper"),
    DTO("DTO / Request / Response"),
    CONFIGURATION("Configuration"),
    COMPONENT("Component"),
    DOMAIN("Domain / Entity"),
    UNKNOWN("未识别");

    private final String label;

    JavaWebRole(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

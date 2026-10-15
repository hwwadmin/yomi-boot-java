package com.yomi.boot.java.qucik.starter.testrbac.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * RBAC 流程测试结果
 */
@Data
public class TestRbacFlowResult {

    /** 流程步骤说明 */
    private List<String> steps = new ArrayList<>();

    /** 受保护的演示 API 路径 */
    private String protectedPath;

    /** 应有权限的用户 ID */
    private long allowedUserId;

    /** 应无权限的用户 ID */
    private long deniedUserId;

}

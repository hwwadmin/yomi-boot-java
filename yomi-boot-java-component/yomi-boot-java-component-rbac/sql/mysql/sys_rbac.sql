-- MySQL 建表脚本（PostgreSQL 版见同组件 sql/pgsql/sys_rbac.sql；启动期缺表时按当前数据库自动执行，也可手工执行）
-- RBAC 权限模型（字段与实体一一对应；status：0 禁用 / 1 启用）

CREATE TABLE IF NOT EXISTS `sys_rbac_perm`
(
    `id`           bigint       NOT NULL COMMENT '主键ID（雪花ID）',
    `code`         varchar(100) NOT NULL COMMENT '权限编码',
    `name`         varchar(200) NOT NULL COMMENT '权限名称',
    `type`         varchar(20)  NOT NULL COMMENT 'menu / api',
    `ui_path`      varchar(500)          DEFAULT NULL COMMENT '侧栏路由（menu）',
    `http_method`  varchar(20)           DEFAULT NULL COMMENT 'HTTP 方法（api）',
    `http_path`    varchar(500)          DEFAULT NULL COMMENT 'HTTP 路径（api）',
    `status`       tinyint      NOT NULL DEFAULT 1 COMMENT '0 禁用 / 1 启用',
    `remark`       varchar(500)          DEFAULT NULL COMMENT '备注',
    `create_time`  datetime(3)           DEFAULT NULL COMMENT '创建时间',
    `create_by`    bigint                DEFAULT NULL COMMENT '创建人ID',
    `update_time`  datetime(3)           DEFAULT NULL COMMENT '更新时间',
    `update_by`    bigint                DEFAULT NULL COMMENT '更新人ID',
    `delete_time`  datetime(3)           DEFAULT NULL COMMENT '删除时间',
    `delete_by`    bigint                DEFAULT NULL COMMENT '删除人ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_type_status` (`type`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RBAC 权限';

CREATE TABLE IF NOT EXISTS `sys_rbac_perm_group`
(
    `id`           bigint       NOT NULL COMMENT '主键ID',
    `code`         varchar(100) NOT NULL COMMENT '权限组编码',
    `name`         varchar(200) NOT NULL COMMENT '权限组名称',
    `status`       tinyint      NOT NULL DEFAULT 1 COMMENT '0 禁用 / 1 启用',
    `remark`       varchar(500)          DEFAULT NULL COMMENT '备注',
    `create_time`  datetime(3)           DEFAULT NULL,
    `create_by`    bigint                DEFAULT NULL,
    `update_time`  datetime(3)           DEFAULT NULL,
    `update_by`    bigint                DEFAULT NULL,
    `delete_time`  datetime(3)           DEFAULT NULL,
    `delete_by`    bigint                DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RBAC 权限组';

CREATE TABLE IF NOT EXISTS `sys_rbac_role`
(
    `id`           bigint       NOT NULL COMMENT '主键ID',
    `code`         varchar(100) NOT NULL COMMENT '角色编码',
    `name`         varchar(200) NOT NULL COMMENT '角色名称',
    `builtin`      tinyint      NOT NULL DEFAULT 0 COMMENT '1 内置 / 0 普通',
    `status`       tinyint      NOT NULL DEFAULT 1 COMMENT '0 禁用 / 1 启用',
    `remark`       varchar(500)          DEFAULT NULL COMMENT '备注',
    `create_time`  datetime(3)           DEFAULT NULL,
    `create_by`    bigint                DEFAULT NULL,
    `update_time`  datetime(3)           DEFAULT NULL,
    `update_by`    bigint                DEFAULT NULL,
    `delete_time`  datetime(3)           DEFAULT NULL,
    `delete_by`    bigint                DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RBAC 角色';

CREATE TABLE IF NOT EXISTS `sys_rbac_user_role_rel`
(
    `id`           bigint NOT NULL COMMENT '主键ID',
    `user_id`      bigint NOT NULL COMMENT '用户ID',
    `role_id`      bigint NOT NULL COMMENT '角色ID',
    `create_time`  datetime(3) DEFAULT NULL,
    `create_by`    bigint      DEFAULT NULL,
    `update_time`  datetime(3) DEFAULT NULL,
    `update_by`    bigint      DEFAULT NULL,
    `delete_time`  datetime(3) DEFAULT NULL,
    `delete_by`    bigint      DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RBAC 用户-角色';

CREATE TABLE IF NOT EXISTS `sys_rbac_role_group_rel`
(
    `id`           bigint NOT NULL COMMENT '主键ID',
    `role_id`      bigint NOT NULL COMMENT '角色ID',
    `group_id`     bigint NOT NULL COMMENT '权限组ID',
    `create_time`  datetime(3) DEFAULT NULL,
    `create_by`    bigint      DEFAULT NULL,
    `update_time`  datetime(3) DEFAULT NULL,
    `update_by`    bigint      DEFAULT NULL,
    `delete_time`  datetime(3) DEFAULT NULL,
    `delete_by`    bigint      DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_group` (`role_id`, `group_id`),
    KEY `idx_role_id` (`role_id`),
    KEY `idx_group_id` (`group_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RBAC 角色-权限组';

CREATE TABLE IF NOT EXISTS `sys_rbac_group_perm_rel`
(
    `id`           bigint NOT NULL COMMENT '主键ID',
    `group_id`     bigint NOT NULL COMMENT '权限组ID',
    `perm_id`      bigint NOT NULL COMMENT '权限ID',
    `create_time`  datetime(3) DEFAULT NULL,
    `create_by`    bigint      DEFAULT NULL,
    `update_time`  datetime(3) DEFAULT NULL,
    `update_by`    bigint      DEFAULT NULL,
    `delete_time`  datetime(3) DEFAULT NULL,
    `delete_by`    bigint      DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_group_perm` (`group_id`, `perm_id`),
    KEY `idx_group_id` (`group_id`),
    KEY `idx_perm_id` (`perm_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='RBAC 权限组-权限';

-- 内置超管角色（固定主键，与 RbacBuiltinRole.SUPER_ADMIN_ROLE_ID 一致；勿删勿改 code/builtin/status）
INSERT INTO `sys_rbac_role` (`id`, `code`, `name`, `builtin`, `status`, `remark`, `create_time`, `update_time`)
SELECT 900000000000000001, 'super_admin', '超级管理员', 1, 1, '内置角色，不可删除或禁用', NOW(3), NOW(3)
WHERE NOT EXISTS (SELECT 1 FROM `sys_rbac_role` WHERE `code` = 'super_admin' LIMIT 1);

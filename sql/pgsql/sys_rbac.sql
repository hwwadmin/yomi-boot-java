-- PostgreSQL 建表脚本（MySQL 版见仓库根 sql/mysql/sys_rbac.sql；属组件表，需手工执行）
-- 时间列统一 timestamptz(3)（即 timestamp(3) with time zone，瞬时语义、与 Java Instant 对齐；MySQL 版对应 datetime(3)）
-- 约束/索引名在 PG 内是 schema 级唯一（MySQL 是表级），故统一带表名前缀（MySQL 的 uk_code 在本文件里按表展开）
-- RBAC 权限模型（字段与实体一一对应；status：0 禁用 / 1 启用）

CREATE TABLE IF NOT EXISTS sys_rbac_perm
(
    id          bigint       NOT NULL,
    code        varchar(100) NOT NULL,
    name        varchar(200) NOT NULL,
    type        varchar(20)  NOT NULL,
    ui_path     varchar(500),
    http_method varchar(20),
    http_path   varchar(500),
    status      smallint     NOT NULL DEFAULT 1,
    remark      varchar(500),
    create_time timestamptz(3),
    create_by   bigint,
    update_time timestamptz(3),
    update_by   bigint,
    delete_time timestamptz(3),
    delete_by   bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_rbac_perm_code UNIQUE (code)
);
CREATE INDEX IF NOT EXISTS idx_sys_rbac_perm_type_status ON sys_rbac_perm (type, status);
COMMENT ON TABLE sys_rbac_perm IS 'RBAC 权限';
COMMENT ON COLUMN sys_rbac_perm.id IS '主键ID（雪花ID）';
COMMENT ON COLUMN sys_rbac_perm.code IS '权限编码';
COMMENT ON COLUMN sys_rbac_perm.name IS '权限名称';
COMMENT ON COLUMN sys_rbac_perm.type IS 'menu / api';
COMMENT ON COLUMN sys_rbac_perm.ui_path IS '侧栏路由（menu）';
COMMENT ON COLUMN sys_rbac_perm.http_method IS 'HTTP 方法（api）';
COMMENT ON COLUMN sys_rbac_perm.http_path IS 'HTTP 路径（api）';
COMMENT ON COLUMN sys_rbac_perm.status IS '0 禁用 / 1 启用';
COMMENT ON COLUMN sys_rbac_perm.remark IS '备注';
COMMENT ON COLUMN sys_rbac_perm.create_time IS '创建时间';
COMMENT ON COLUMN sys_rbac_perm.create_by IS '创建人ID';
COMMENT ON COLUMN sys_rbac_perm.update_time IS '更新时间';
COMMENT ON COLUMN sys_rbac_perm.update_by IS '更新人ID';
COMMENT ON COLUMN sys_rbac_perm.delete_time IS '删除时间';
COMMENT ON COLUMN sys_rbac_perm.delete_by IS '删除人ID';

CREATE TABLE IF NOT EXISTS sys_rbac_perm_group
(
    id          bigint       NOT NULL,
    code        varchar(100) NOT NULL,
    name        varchar(200) NOT NULL,
    status      smallint     NOT NULL DEFAULT 1,
    remark      varchar(500),
    create_time timestamptz(3),
    create_by   bigint,
    update_time timestamptz(3),
    update_by   bigint,
    delete_time timestamptz(3),
    delete_by   bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_rbac_perm_group_code UNIQUE (code)
);
COMMENT ON TABLE sys_rbac_perm_group IS 'RBAC 权限组';
COMMENT ON COLUMN sys_rbac_perm_group.id IS '主键ID';
COMMENT ON COLUMN sys_rbac_perm_group.code IS '权限组编码';
COMMENT ON COLUMN sys_rbac_perm_group.name IS '权限组名称';
COMMENT ON COLUMN sys_rbac_perm_group.status IS '0 禁用 / 1 启用';
COMMENT ON COLUMN sys_rbac_perm_group.remark IS '备注';

CREATE TABLE IF NOT EXISTS sys_rbac_role
(
    id          bigint       NOT NULL,
    code        varchar(100) NOT NULL,
    name        varchar(200) NOT NULL,
    builtin     smallint     NOT NULL DEFAULT 0,
    status      smallint     NOT NULL DEFAULT 1,
    remark      varchar(500),
    create_time timestamptz(3),
    create_by   bigint,
    update_time timestamptz(3),
    update_by   bigint,
    delete_time timestamptz(3),
    delete_by   bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_rbac_role_code UNIQUE (code)
);
COMMENT ON TABLE sys_rbac_role IS 'RBAC 角色';
COMMENT ON COLUMN sys_rbac_role.id IS '主键ID';
COMMENT ON COLUMN sys_rbac_role.code IS '角色编码';
COMMENT ON COLUMN sys_rbac_role.name IS '角色名称';
COMMENT ON COLUMN sys_rbac_role.builtin IS '1 内置 / 0 普通';
COMMENT ON COLUMN sys_rbac_role.status IS '0 禁用 / 1 启用';
COMMENT ON COLUMN sys_rbac_role.remark IS '备注';

CREATE TABLE IF NOT EXISTS sys_rbac_user_role_rel
(
    id          bigint NOT NULL,
    user_id     bigint NOT NULL,
    role_id     bigint NOT NULL,
    create_time timestamptz(3),
    create_by   bigint,
    update_time timestamptz(3),
    update_by   bigint,
    delete_time timestamptz(3),
    delete_by   bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_rbac_user_role_rel_user_role UNIQUE (user_id, role_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_rbac_user_role_rel_user_id ON sys_rbac_user_role_rel (user_id);
CREATE INDEX IF NOT EXISTS idx_sys_rbac_user_role_rel_role_id ON sys_rbac_user_role_rel (role_id);
COMMENT ON TABLE sys_rbac_user_role_rel IS 'RBAC 用户-角色';
COMMENT ON COLUMN sys_rbac_user_role_rel.id IS '主键ID';
COMMENT ON COLUMN sys_rbac_user_role_rel.user_id IS '用户ID';
COMMENT ON COLUMN sys_rbac_user_role_rel.role_id IS '角色ID';

CREATE TABLE IF NOT EXISTS sys_rbac_role_group_rel
(
    id          bigint NOT NULL,
    role_id     bigint NOT NULL,
    group_id    bigint NOT NULL,
    create_time timestamptz(3),
    create_by   bigint,
    update_time timestamptz(3),
    update_by   bigint,
    delete_time timestamptz(3),
    delete_by   bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_rbac_role_group_rel_role_group UNIQUE (role_id, group_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_rbac_role_group_rel_role_id ON sys_rbac_role_group_rel (role_id);
CREATE INDEX IF NOT EXISTS idx_sys_rbac_role_group_rel_group_id ON sys_rbac_role_group_rel (group_id);
COMMENT ON TABLE sys_rbac_role_group_rel IS 'RBAC 角色-权限组';
COMMENT ON COLUMN sys_rbac_role_group_rel.id IS '主键ID';
COMMENT ON COLUMN sys_rbac_role_group_rel.role_id IS '角色ID';
COMMENT ON COLUMN sys_rbac_role_group_rel.group_id IS '权限组ID';

CREATE TABLE IF NOT EXISTS sys_rbac_group_perm_rel
(
    id          bigint NOT NULL,
    group_id    bigint NOT NULL,
    perm_id     bigint NOT NULL,
    create_time timestamptz(3),
    create_by   bigint,
    update_time timestamptz(3),
    update_by   bigint,
    delete_time timestamptz(3),
    delete_by   bigint,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_rbac_group_perm_rel_group_perm UNIQUE (group_id, perm_id)
);
CREATE INDEX IF NOT EXISTS idx_sys_rbac_group_perm_rel_group_id ON sys_rbac_group_perm_rel (group_id);
CREATE INDEX IF NOT EXISTS idx_sys_rbac_group_perm_rel_perm_id ON sys_rbac_group_perm_rel (perm_id);
COMMENT ON TABLE sys_rbac_group_perm_rel IS 'RBAC 权限组-权限';
COMMENT ON COLUMN sys_rbac_group_perm_rel.id IS '主键ID';
COMMENT ON COLUMN sys_rbac_group_perm_rel.group_id IS '权限组ID';
COMMENT ON COLUMN sys_rbac_group_perm_rel.perm_id IS '权限ID';

-- 内置超管角色（固定主键，与 RbacBuiltinRole.SUPER_ADMIN_ROLE_ID 一致；勿删勿改 code/builtin/status）
INSERT INTO sys_rbac_role (id, code, name, builtin, status, remark, create_time, update_time)
SELECT 900000000000000001, 'super_admin', '超级管理员', 1, 1, '内置角色，不可删除或禁用',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
WHERE NOT EXISTS (SELECT 1 FROM sys_rbac_role WHERE code = 'super_admin' LIMIT 1);

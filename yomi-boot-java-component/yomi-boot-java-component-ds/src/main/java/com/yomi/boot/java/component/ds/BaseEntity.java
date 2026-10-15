package com.yomi.boot.java.component.ds;

import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yomi.boot.java.base.utils.IdUtils;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Objects;

/**
 * 基础实体
 *
 * @author zwei
 */
@Getter
@Setter
@ToString
@SuperBuilder(toBuilder = true)
@Accessors(chain = true)
@RequiredArgsConstructor
public abstract class BaseEntity implements Table {

    @TableId
    private Long id;

    /** 创建时间 */
    private Instant createTime;

    /** 创建人ID */
    private Long createBy;

    /** 更新时间 */
    private Instant updateTime;

    /** 更新人ID */
    private Long updateBy;

    /** 删除时间 */
    private Instant deleteTime;

    /** 删除人ID */
    private Long deleteBy;

    @JsonIgnore
    public String getIdStr() {
        return Objects.isNull(this.id) ? null : String.valueOf(this.id);
    }

    @JsonIgnore
    public void init() {
        initId();
        initCreateTime();
        this.updateTime = Instant.now();
    }

    @JsonIgnore
    public void update() {
        this.updateTime = Instant.now();
    }

    @JsonIgnore
    public void initId() {
        if (Objects.isNull(this.id)) this.id = IdUtils.nextId();
    }

    @JsonIgnore
    public void initCreateTime() {
        if (Objects.isNull(this.createTime)) this.createTime = Instant.now();
    }

    @JsonIgnore
    public void del() {
        if (Objects.isNull(this.deleteTime)) this.deleteTime = Instant.now();
    }

    @JsonIgnore
    public boolean exist() {
        return Objects.isNull(this.deleteTime);
    }

    /** 已分配主键时仅按 id 计算哈希 */
    @Override
    public int hashCode() {
        if (getId() == null) return super.hashCode();
        return getId().hashCode();
    }

    /** 同类实体且主键相同视为同一行 */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof BaseEntity that && getClass() == that.getClass()) {
            return id != null && Objects.equals(id, that.id);
        }
        return false;
    }

}

package com.yomi.boot.java.component.ds;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.yomi.boot.java.base.utils.Assert;
import com.yomi.boot.java.base.utils.Lists;
import lombok.Getter;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.core.ResolvableType;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 基于MyBatis的DAO封装
 *
 * @author zwei
 */
public abstract class MyBatisDao<M extends BaseMapper<T>, T extends BaseEntity> {

    /** MyBatis Mapper（可配合 mapper.xml） */
    @Getter
    private final M mapper;

    /** 实体类型 */
    private final Class<T> entityClass;

    public MyBatisDao(M mapper) {
        this.mapper = mapper;
        this.entityClass = resolveEntityClass();
    }

    /**
     * 从子类泛型声明推导实体类型
     */
    @SuppressWarnings("unchecked")
    private Class<T> resolveEntityClass() {
        var resolved = ResolvableType.forClass(getClass()).as(MyBatisDao.class).getGeneric(1).resolve();
        Assert.nonNull(resolved, "实体类型推导失败，MyBatisDao子类必须声明完整泛型参数");
        return (Class<T>) resolved;
    }

    /**
     * 获取表描述
     */
    public String getTableDesc() {
        return "table#" + entityClass.getSimpleName();
    }

    /**
     * 获取lambda查询条件（链式调用）
     */
    public LambdaQueryChainWrapper<T> lambdaQuery() {
        return ChainWrappers.lambdaQueryChain(mapper, entityClass);
    }

    /**
     * 获取lambda查询条件（通用条件构造，非链式）
     */
    public LambdaQueryWrapper<T> lambdaQueryCommon() {
        return Wrappers.<T>lambdaQuery();
    }

    /**
     * 获取lambda更新条件
     */
    public LambdaUpdateChainWrapper<T> lambdaUpdate() {
        return ChainWrappers.lambdaUpdateChain(mapper);
    }

    /**
     * 构建分页对象
     * pageNo从1开始，与 MP 的 current 同为 1 基页码，直接传入即可，无需减1
     */
    public Page<T> page(long pageNo, long pageSize) {
        Assert.isTrue(pageNo >= 1, "{}页码不能小于1", getTableDesc());
        Assert.isTrue(pageSize >= 1, "{}每页条数不能小于1", getTableDesc());
        return new Page<>(pageNo, pageSize);
    }

    /**
     * 获取实体（排除删除）
     */
    public T get(Long id) {
        return get(id, true);
    }

    /**
     * 获取实体
     * excludeDeleted 是否排除已删除 true排除
     */
    public T get(Long id, boolean excludeDeleted) {
        Assert.nonNull(id, "{}ID不能为空", getTableDesc());
        T entity = mapper.selectById(id);
        Assert.nonNull(entity, "{}不存在", getTableDesc());
        if (excludeDeleted) {
            Assert.isTrue(entity.exist(), "{}已删除", getTableDesc());
        }
        return entity;
    }

    /**
     * 批量获取对象（排除已删除）
     */
    public List<T> listByIds(Collection<? extends Serializable> idList) {
        return listByIds(idList, true);
    }

    /**
     * 批量获取对象
     */
    public List<T> listByIds(Collection<? extends Serializable> idList, boolean excludeDeleted) {
        if (CollectionUtils.isEmpty(idList)) return Lists.of();
        var entityList = mapper.selectByIds(idList);
        if (CollectionUtils.isEmpty(entityList)) return Lists.of();
        if (!excludeDeleted) return entityList;
        return entityList.stream().filter(BaseEntity::exist).collect(Collectors.toList());
    }

    /**
     * 获取对象
     */
    public List<T> listAll() {
        return lambdaQuery().isNull(T::getDeleteTime).list();
    }

    /**
     * 保存
     */
    public void save(T entity) {
        Assert.nonNull(entity, "{}不能为空", getTableDesc());
        entity.init();
        mapper.insert(entity);
    }

    /**
     * 批量保存
     */
    public void saveBatch(Collection<T> entityList) {
        if (CollectionUtils.isEmpty(entityList)) return;
        entityList.forEach(T::init);
        mapper.insert(entityList);
    }

    /**
     * 更新（id不存在时抛出异常）
     * 批量见 updateBatch：id不存在的行不更新、不抛错
     */
    public void update(T entity) {
        Assert.nonNull(entity, "{}不能为空", getTableDesc());
        Assert.nonNull(entity.getId(), "{}ID不能为空", getTableDesc());
        entity.update();
        int rows = mapper.updateById(entity);
        Assert.isTrue(rows == 1, "{}更新失败，ID[{}]不存在", getTableDesc(), entity.getId());
    }

    /**
     * 批量更新
     * id不存在的行不更新、不抛错，受影响行数不做校验（可由 BatchResult.getUpdateCounts() 自查）；
     * 需要"未命中即失败"的严格语义请用单条 update()
     */
    public void updateBatch(Collection<T> entityList) {
        if (CollectionUtils.isEmpty(entityList)) return;
        entityList.forEach(T::update);
        mapper.updateById(entityList);
    }

    /**
     * 保存/更新
     * id为空新增，id非空更新（不查库校验存在性，id不存在时更新失败抛错）
     * 批量见 saveOrUpdateBatch：id不存在的更新行不更新、不补插
     */
    public void saveOrUpdate(T entity) {
        Assert.nonNull(entity, "{}不能为空", getTableDesc());
        if (Objects.isNull(entity.getId())) {
            save(entity);
        } else {
            update(entity);
        }
    }

    /**
     * 批量保存/更新
     * id为空的新增、id非空的更新，分组后各执行一次批量SQL，不逐条查库判断存在性
     * id非空但库中不存在的行更新未命中，不补插、不抛错，与单条 saveOrUpdate() 的抛错语义不同
     */
    public void saveOrUpdateBatch(Collection<T> entityList) {
        if (CollectionUtils.isEmpty(entityList)) return;
        var insertList = new ArrayList<T>();
        var updateList = new ArrayList<T>();
        entityList.forEach(entity -> {
            if (Objects.isNull(entity.getId())) {
                entity.init();
                insertList.add(entity);
            } else {
                entity.update();
                updateList.add(entity);
            }
        });
        if (!insertList.isEmpty()) mapper.insert(insertList);
        if (!updateList.isEmpty()) mapper.updateById(updateList);
    }

    /**
     * 物理删除单条记录（无匹配行时视为成功，操作幂等）
     */
    public void del(T entity) {
        Assert.nonNull(entity, "{}不能为空", getTableDesc());
        Assert.nonNull(entity.getId(), "{}ID不能为空", getTableDesc());
        mapper.deleteById(entity.getId());
    }

    /**
     * 物理删除单条记录（无匹配行时视为成功，操作幂等）
     */
    public void del(Long id) {
        Assert.nonNull(id, "{}ID不能为空", getTableDesc());
        mapper.deleteById(id);
    }

    /** 批量物理删除（无匹配行时仍视为成功） */
    public void delBatch(Collection<T> entityList) {
        if (CollectionUtils.isEmpty(entityList)) return;
        entityList.forEach(entity -> Assert.nonNull(entity.getId(), "{}ID不能为空", getTableDesc()));
        var idList = entityList.stream().map(BaseEntity::getId).collect(Collectors.toList());
        mapper.deleteByIds(idList);
    }

    /**
     * 根据条件物理删除
     * Wrapper只允许LambdaQueryWrapper
     */
    public void del(Wrapper<T> wrapper) {
        Assert.nonNull(wrapper, "删除条件不能为空");
        Assert.isTrue(wrapper instanceof LambdaQueryWrapper, "删除方法只支持LambdaQueryWrapper");
        mapper.delete(wrapper);
    }

    /**
     * 逻辑删除（重复删除视为成功）
     */
    public void softDel(T entity) {
        Assert.nonNull(entity, "{}不能为空", getTableDesc());
        Assert.nonNull(entity.getId(), "{}ID不能为空", getTableDesc());
        entity.del();
        softDelById(entity.getId(), entity.getDeleteTime());
    }

    /** 按 ID 逻辑删除，删除时间取当前时刻 */
    public void softDel(Long id) {
        Assert.nonNull(id, "{}ID不能为空", getTableDesc());
        softDelById(id, Instant.now());
    }

    /** 批量逻辑删除 */
    public void softDelBatch(Collection<T> entityList) {
        if (CollectionUtils.isEmpty(entityList)) return;
        entityList.forEach(entity -> Assert.nonNull(entity.getId(), "{}ID不能为空", getTableDesc()));
        entityList.forEach(T::del);
        var idList = entityList.stream().map(BaseEntity::getId).collect(Collectors.toList());
        lambdaUpdate()
                .set(T::getDeleteTime, Instant.now())
                .set(T::getUpdateTime, Instant.now())
                .isNull(T::getDeleteTime)
                .in(T::getId, idList)
                .update();
    }

    /**
     * 按 ID 逻辑删除：已删除行或不存在的主键跳过（幂等）
     */
    private void softDelById(Long id, Instant deleteTime) {
        lambdaUpdate()
                .set(T::getDeleteTime, deleteTime)
                .set(T::getUpdateTime, deleteTime)
                .isNull(T::getDeleteTime)
                .eq(T::getId, id)
                .update();
    }

}

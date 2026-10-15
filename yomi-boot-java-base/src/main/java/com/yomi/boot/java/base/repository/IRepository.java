package com.yomi.boot.java.base.repository;

import com.yomi.boot.java.base.exception.NotImplException;

import java.util.List;
import java.util.function.Supplier;

/**
 * 通用仓储接口
 *
 * @param <ENTITY> 领域对象类型
 * @param <ID>     主键类型
 * @author zwei
 */
public interface IRepository<ENTITY, ID> {

    /**
     * 按主键查询领域对象, 不存在则返回null
     *
     * @param id 主键ID
     * @return 领域对象
     */
    default ENTITY getById(ID id) {
        throw NotImplException.exception("Repository.getById has not been implemented.");
    }

    /**
     * 按主键查询领域对象，不存在则抛出指定异常
     *
     * @param id                主键ID
     * @param exceptionSupplier 异常提供者
     * @return 领域对象
     */
    default ENTITY requireById(ID id, Supplier<? extends RuntimeException> exceptionSupplier) {
        ENTITY entity = getById(id);
        if (entity == null) {
            throw exceptionSupplier.get();
        }
        return entity;
    }

    /**
     * 按主键查询领域对象列表
     *
     * @param ids 主键ID列表
     * @return 领域对象列表
     */
    default List<ENTITY> listByIds(List<ID> ids) {
        throw NotImplException.exception("Repository.listByIds has not been implemented.");
    }

    /**
     * 查询全部领域对象列表
     *
     * @return 领域对象列表
     */
    default List<ENTITY> listAll() {
        throw NotImplException.exception("Repository.listAll has not been implemented.");
    }

    /**
     * 保存领域对象
     *
     * @param entity     领域对象
     * @param operatorId 操作人ID
     */
    default void save(ENTITY entity, Long operatorId) {
        throw NotImplException.exception("Repository.save has not been implemented.");
    }

    /**
     * 批量保存领域对象
     *
     * @param entities   领域对象列表
     * @param operatorId 操作人ID
     */
    default void saveBatch(List<ENTITY> entities, Long operatorId) {
        throw NotImplException.exception("Repository.saveBatch has not been implemented.");
    }

    /**
     * 更新领域对象
     *
     * @param entity     领域对象
     * @param operatorId 操作人ID
     */
    default void update(ENTITY entity, Long operatorId) {
        throw NotImplException.exception("Repository.update has not been implemented.");
    }

    /**
     * 批量更新领域对象
     *
     * @param entities   领域对象列表
     * @param operatorId 操作人ID
     */
    default void updateBatch(List<ENTITY> entities, Long operatorId) {
        throw NotImplException.exception("Repository.updateBatch has not been implemented.");
    }

    /**
     * 删除领域对象
     *
     * @param id         主键ID
     * @param operatorId 操作人ID
     */
    default void delete(ID id, Long operatorId) {
        throw NotImplException.exception("Repository.delete has not been implemented.");
    }

    /**
     * 批量删除领域对象
     *
     * @param ids        主键ID列表
     * @param operatorId 操作人ID
     */
    default void deleteBatch(List<ID> ids, Long operatorId) {
        throw NotImplException.exception("Repository.deleteBatch has not been implemented.");
    }

}

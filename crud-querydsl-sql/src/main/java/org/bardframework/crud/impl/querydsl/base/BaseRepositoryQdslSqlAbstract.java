package org.bardframework.crud.impl.querydsl.base;

import com.querydsl.core.dml.StoreClause;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.QBean;
import com.querydsl.core.types.dsl.*;
import com.querydsl.sql.RelationalPathBase;
import com.querydsl.sql.SQLQuery;
import com.querydsl.sql.SQLQueryFactory;
import com.querydsl.sql.dml.SQLInsertClause;
import com.querydsl.sql.dml.SQLUpdateClause;
import org.apache.commons.collections4.CollectionUtils;
import org.bardframework.commons.utils.AssertionUtils;
import org.bardframework.crud.api.base.BaseCriteriaAbstract;
import org.bardframework.crud.api.base.BaseModelAbstract;
import org.bardframework.crud.api.base.BaseRepository;
import org.bardframework.crud.api.filter.Filter;
import org.bardframework.crud.api.filter.IdFilter;
import org.bardframework.crud.api.filter.RangeFilter;
import org.bardframework.crud.api.filter.StringFilter;
import org.bardframework.crud.api.util.PaginationUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.lang.Nullable;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Created by vahid on 1/17/17.
 */
public abstract class BaseRepositoryQdslSqlAbstract<M extends BaseModelAbstract<I>, C extends BaseCriteriaAbstract<I>, I extends Comparable<? super I>, U> implements BaseRepository<M, C, I, U> {

    private static final int DEFAULT_SIZE = 20;

    protected final Logger LOGGER = LoggerFactory.getLogger(getClass());
    protected final Class<M> modelClazz;
    protected final Class<C> criteriaClazz;
    private final SQLQueryFactory queryFactory;

    public BaseRepositoryQdslSqlAbstract(SQLQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
        ParameterizedType parameterizedType = null;
        Class<?> targetClazz = this.getClass();
        while (!(null != parameterizedType && parameterizedType.getActualTypeArguments().length >= 2) && null != targetClazz) {
            parameterizedType = targetClazz.getGenericSuperclass() instanceof ParameterizedType ? (ParameterizedType) targetClazz.getGenericSuperclass() : null;
            targetClazz = targetClazz.getSuperclass();
        }
        try {
            this.modelClazz = (Class<M>) ((ParameterizedType) this.getClass().getGenericSuperclass()).getActualTypeArguments()[0];
            this.criteriaClazz = (Class<C>) parameterizedType.getActualTypeArguments()[1];
        } catch (Exception e) {
            this.LOGGER.debug("can't determine class from generic type!", e);
            throw new IllegalArgumentException("can't determine class from generic type!", e);
        }
    }

    protected abstract <T> SQLQuery<T> setCriteria(C criteria, SQLQuery<T> query, U user);

    protected abstract RelationalPathBase<?> getEntity();

    protected abstract QBean<M> getQBean();

    protected abstract <T extends StoreClause<T>> T toClause(T clause, M model, U user);

    protected abstract M setIdentifier(M model, U user);

    private static boolean isUnpaged(Pageable pageable) {
        return pageable.isUnpaged();
    }

    @Override
    public final M getEmptyModel() {
        try {
            return modelClazz.getDeclaredConstructor().newInstance();
        } catch (IllegalAccessException | InstantiationException | NoSuchMethodException |
                 InvocationTargetException e) {
            this.LOGGER.error("can't instantiate model class using empty constructor {}", this.modelClazz, e);
            throw new IllegalArgumentException("can't instantiate model class using empty constructor" + this.modelClazz, e);
        }
    }

    @Override
    public final C getEmptyCriteria() {
        try {
            return criteriaClazz.getDeclaredConstructor().newInstance();
        } catch (IllegalAccessException | InstantiationException | NoSuchMethodException |
                 InvocationTargetException e) {
            this.LOGGER.error("can't instantiate criteria class using empty constructor {}", this.criteriaClazz, e);
            throw new IllegalArgumentException("can't instantiate criteria class using empty constructor" + this.criteriaClazz, e);
        }
    }

    protected <T extends StoreClause<T>> T fillClause(T clause, M model, U user) {
        clause = this.toClause(clause, model, user);
        for (Class<?> clazz : this.getClass().getInterfaces()) {
            if (WriteExtendedRepositoryQdslSql.class.isAssignableFrom(clazz)) {
                ((WriteExtendedRepositoryQdslSql) this).process(clause, model, user);
            }
        }
        return clause;
    }

    @Transactional
    @Override
    public M save(M model, U user) {
        return this.save(Collections.singletonList(model), user).get(0);
    }

    /**
     * @return not changed models if models is null or empty, saved models otherwise.
     */
    @Transactional
    @Override
    public List<M> save(List<M> models, U user) {
        if (CollectionUtils.isEmpty(models)) {
            return models;
        }
        SQLInsertClause insertClause = this.getQueryFactory().insert(this.getEntity());
        models.forEach(model -> {
            this.fillClause(insertClause, model, user);
            this.setIdentifier(model, user);
            AssertionUtils.notNull(model.getId(), "model identifier is not provided in 'setIdentifier' method");
                    insertClause.set(getIdentifierPath(), model.getId());
                    insertClause.addBatch();
                }
        );
        long affectedRowsCount = insertClause.execute();
        if (models.size() != affectedRowsCount) {
            LOGGER.warn("expect insert '{}' row, but '{}' row(s) inserted.", models.size(), affectedRowsCount);
//            throw new IllegalStateException("expect affect '" + models.size() + "' row, but " + affectedRowsCount + " row(s) inserted.");
        }
        return models;
    }

    @Transactional
    @Override
    public M update(M model, U user) {
        SQLUpdateClause updateClause = this.getQueryFactory().update(getEntity()).where(this.getIdentifierPath().eq(model.getId()));
        updateClause = this.fillClause(updateClause, model, user);
        long affectedRowsCount = updateClause.execute();
        if (1 != affectedRowsCount) {
            throw new IllegalStateException("expect affect one row, but " + affectedRowsCount + " row(s) updated.");
        }
        return model;
    }

    @Transactional
    @Override
    public Optional<M> patch(I id, Map<String, Object> fields, U user) {
        final SQLUpdateClause updateClause = this.getQueryFactory().update(this.getEntity()).where(this.getIdentifierPath().eq(id));

        List<Path<?>> columns = this.getEntity().getColumns();
        for (Path<?> column : columns) {
            String key = column.getMetadata().getName();
            if (fields.containsKey(key)) {
                updateClause.set((Path<Object>) column, fields.get(key));
            }
        }

        long affectedRowsCount = updateClause.execute();
        if (1 != affectedRowsCount) {
            throw new IllegalStateException("expect affect one row, but " + affectedRowsCount + " row(s) updated.");
        }
        return this.get(id, user);
    }

    public abstract <T extends ComparableExpression<I>> T getIdentifierPath();

    @Transactional(readOnly = true)
    @Override
    public Optional<M> get(I identifier, U user) {
        AssertionUtils.notNull(identifier, "Given Identifier cannot be null.");
        C criteria = this.getEmptyCriteria();
        criteria.setId((IdFilter<I>) new IdFilter<I>().setEquals(identifier));
        return this.getOne(criteria, user);
    }

    @Transactional(readOnly = true)
    @Override
    public List<M> get(List<I> ids, U user) {
        AssertionUtils.notNull(ids, "Given Identifiers cannot be null.");
        C criteria = this.getEmptyCriteria();
        criteria.setId((IdFilter<I>) new IdFilter<I>().setIn(ids));
        return this.get(criteria, user);
    }

    private SQLQuery<?> reuseQuery(SQLQuery<?> query) {
        return query.clone(this.getQueryFactory().getConnection());
    }

    public SQLQuery<?> prepareQuery(C criteria, @Nullable Sort sort, U user) {
        SQLQuery<?> query = this.getQueryFactory().query();
        query.from(this.getEntity());
        query = this.setJoins(query, user);
        query = this.setCriteria(criteria, query, user);

        if (null != criteria.getId()) {
            query.where(buildQuery(criteria.getId(), this.getIdentifierPath()));
        }

        for (Class<?> clazz : this.getClass().getInterfaces()) {
            if (ReadExtendedRepositoryQdslSql.class.isAssignableFrom(clazz)) {
                ((ReadExtendedRepositoryQdslSql<C, ?, U>) this).process(criteria, query, user);
            }
        }
        query = this.setOrders(query, criteria, sort, user);
        return query;
    }

    @Transactional(readOnly = true)
    @Override
    public Page<M> get(C criteria, Pageable pageable, U user) {
        AssertionUtils.notNull(criteria, "null criteria not acceptable");
        SQLQuery<?> query = this.prepareQuery(criteria, pageable.getSort(), user);
        long count = query.fetchCount();
        if (0 > count) {
            return Page.empty();
        }
        query = this.reuseQuery(query);
        return isUnpaged(pageable) ? new PageImpl<>(this.getList(query)) : this.readPage(query, pageable, () -> count);
    }

    private List<M> getList(SQLQuery<?> query) {
        return query.select(this.getQBean()).fetch();
    }

    protected Page<M> readPage(SQLQuery<?> query, Pageable pageable, LongSupplier totalSupplier) {
        if (pageable.isPaged()) {
            query = this.setPageAndSize(query, pageable);
        }

        return PaginationUtil.getPage(this.getList(query), pageable, totalSupplier);
    }

    public <T> SQLQuery<T> setPageAndSize(SQLQuery<T> query, Pageable pageable) {
        query.offset(pageable.getPageSize() == 0 ? (long) (Math.max(pageable.getPageNumber(), 0)) * DEFAULT_SIZE : pageable.getOffset());
        query.limit(pageable.getPageSize() == 0 ? DEFAULT_SIZE : pageable.getPageSize());
        return query;
    }

    @Transactional(readOnly = true)
    @Override
    public long getCount(C criteria, U user) {
        return this.prepareQuery(criteria, null, user).fetchCount();
    }

    @Transactional(readOnly = true)
    @Override
    public boolean isExist(C criteria, U user) {
        return this.getCount(criteria, user) > 0;
    }

    @Transactional(readOnly = true)
    @Override
    public boolean isNotExist(C criteria, U user) {
        return this.getCount(criteria, user) == 0;
    }

    @Transactional
    @Override
    public long delete(I id, U user) {
        AssertionUtils.notNull(id, "id should not be null.");
        return this.delete(Collections.singletonList(id), user);
    }

    @Transactional
    @Override
    public long delete(List<I> ids, U user) {
        AssertionUtils.notNull(ids, "ids should not be null.");
        C criteria = this.getEmptyCriteria();
        criteria.setId((IdFilter<I>) new IdFilter<I>().setIn(ids));

        return this.delete(criteria, user);
    }

    @Transactional
    @Override
    public long delete(C criteria, U user) {
        AssertionUtils.notNull(criteria, "Criteria object should not be null.");
        return this.getQueryFactory().delete(this.getEntity())
                .where(this.getIdentifierPath().in(this.getIds(criteria, user)))
                .execute();
    }

    @Transactional
    @Override
    public long directDelete(List<I> ids, U user) {
        AssertionUtils.notEmpty(ids, "ids should not be empty.");
        return this.getQueryFactory().delete(this.getEntity())
                .where(this.getIdentifierPath().in(ids))
                .execute();
    }

    @Transactional(readOnly = true)
    @Override
    public List<I> getIds(C criteria, U user) {
        return this.prepareQuery(criteria, null, user).select(this.getIdentifierPath()).fetch();
    }

    @Transactional(readOnly = true)
    @Override
    public List<M> get(C criteria, U user) {
        return this.get(criteria, (Sort) null, user);
    }

    @Transactional(readOnly = true)
    @Override
    public List<M> get(C criteria, Sort sort, U user) {
        AssertionUtils.notNull(criteria, "Given criteria cannot be null");
        return this.prepareQuery(criteria, sort, user).select(this.getQBean()).fetch();
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<M> getOne(C criteria, U user) {
        AssertionUtils.notNull(criteria, "Given criteria cannot be null");
        return Optional.ofNullable(this.prepareQuery(criteria, null, user).select(this.getQBean()).fetchOne());
    }

    protected <T> SQLQuery<T> setJoins(SQLQuery<T> query, U user) {
        return query;
    }

    public <R> SQLQuery<R> setOrders(SQLQuery<R> query, C criteria, @Nullable Sort sort, U user) {
        List<OrderSpecifier<?>> list = this.toOrders(sort);
        query.orderBy(list.toArray(new OrderSpecifier[0]));
        return query;
    }

    public SQLQueryFactory getQueryFactory() {
        return queryFactory;
    }

    protected <I extends Comparable<? super I>> I safeFetchId(BaseModelAbstract<I> model) {
        return null == model ? null : model.getId();
    }

    @Transactional(readOnly = true)
    public List<M> getAll(U user) {
        return this.get(this.getEmptyCriteria(), user);
    }

    protected List<OrderSpecifier<?>> toOrders(Sort sort) {
        Map<String, OrderField> map = this.getSortColumns();

        if (sort == null || sort.isUnsorted()) {
            return map.values().stream()
                    .map(OrderField::getColumnDirection).collect(Collectors.toList());
        }

        return sort.stream()
                .filter(b -> map.containsKey(b.getProperty()))
                .map(b -> b.isAscending() ? map.get(b.getProperty()).getColumn().asc() : map.get(b.getProperty()).getColumn().desc()).collect(Collectors.toList());
    }

    protected Map<String, OrderField> getSortColumns() {
        Map<String, OrderField> map = new HashMap<>();
        map.put("id", new OrderField(this.getIdentifierPath(), Order.ASC));
        return map;
    }

    protected <X extends Comparable<? super X>> BooleanExpression buildQuery(Filter<X> filter, ComparableExpression<X> expression) {
        if (filter.getEquals() != null) {
            return expression.eq(filter.getEquals());
        } else if (filter.getIn() != null) {
            return expression.in(filter.getIn());
        } else if (filter.getNotEquals() != null) {
            return expression.ne(filter.getNotEquals());
        } else if (filter.getNotIn() != null) {
            return expression.notIn(filter.getNotIn());
        } else if (filter.getSpecified() != null) {
            if (filter.getSpecified()) {
                return expression.isNotNull();
            } else {
                return expression.isNull();
            }
        }
        return Expressions.asBoolean(true).isTrue();
    }

    protected BooleanExpression buildQuery(StringFilter filter, StringExpression expression) {
        if (filter.getEquals() != null) {
            return expression.eq(filter.getEquals());
        } else if (filter.getIn() != null) {
            return expression.in(filter.getIn());
        } else if (filter.getContains() != null) {
//            return likeUpperSpecification(metaclassFunction, filter.getContains());
            return expression.likeIgnoreCase("%" + filter.getContains() + "%");
        } else if (filter.getDoesNotContain() != null) {
            return expression.notLike("%" + filter.getDoesNotContain() + "%");
        } else if (filter.getNotEquals() != null) {
            return expression.ne(filter.getNotEquals());
        } else if (filter.getNotIn() != null) {
            return expression.notIn(filter.getNotIn());
        } else if (filter.getSpecified() != null) {
            if (filter.getSpecified()) {
                return expression.isNotNull();
            } else {
                return expression.isNull();
            }
        }
        return Expressions.asBoolean(true).isTrue();
    }

    protected <X extends Comparable<? super X>> BooleanExpression buildQuery(RangeFilter<X> filter,
                                                                             ComparableExpression<X> expression) {
        BooleanExpression expr = this.buildQueryInternal(filter, expression);

        if (filter.getGreaterThan() != null) {
            expr = expr.and(expression.gt(filter.getGreaterThan()));
        }
        if (filter.getGreaterThanOrEqual() != null) {
            expr = expr.and(expression.goe(filter.getGreaterThanOrEqual()));
        }
        if (filter.getLessThan() != null) {
            expr = expr.and(expression.lt(filter.getLessThan()));
        }
        if (filter.getLessThanOrEqual() != null) {
            expr = expr.and(expression.loe(filter.getLessThanOrEqual()));
        }
        return expr;
    }

    private <X extends Comparable<? super X>> BooleanExpression buildQueryInternal(RangeFilter<X> filter,
                                                                                   ComparableExpressionBase<X> expression) {
        if (filter.getEquals() != null) {
            return expression.eq(filter.getEquals());
        } else if (filter.getIn() != null) {
            return expression.in(filter.getIn());
        }

        BooleanExpression expr = Expressions.asBoolean(true).isTrue();
        if (filter.getSpecified() != null) {
            if (filter.getSpecified()) {
                expr = expr.and(expression.isNotNull());
            } else {
                expr = expr.and(expression.isNull());
            }
        }
        if (filter.getNotEquals() != null) {
            expr = expr.and(expression.ne(filter.getNotEquals()));
        } else if (filter.getNotIn() != null) {
            expr = expr.and(expression.notIn(filter.getNotIn()));
        }
        return expr;
    }

    protected <X extends Number & Comparable<? super X>> BooleanExpression buildQuery(RangeFilter<X> filter,
                                                                                      NumberExpression<X> expression) {
        BooleanExpression expr = this.buildQueryInternal(filter, expression);

        if (filter.getGreaterThan() != null) {
            expr = expr.and(expression.gt(filter.getGreaterThan()));
        }
        if (filter.getGreaterThanOrEqual() != null) {
            expr = expr.and(expression.goe(filter.getGreaterThanOrEqual()));
        }
        if (filter.getLessThan() != null) {
            expr = expr.and(expression.lt(filter.getLessThan()));
        }
        if (filter.getLessThanOrEqual() != null) {
            expr = expr.and(expression.loe(filter.getLessThanOrEqual()));
        }
        return expr;
    }
}

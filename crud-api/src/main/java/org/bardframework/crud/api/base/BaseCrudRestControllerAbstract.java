package org.bardframework.crud.api.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by Vahid Zafari (v.zafari@chmail.ir) on 1/17/17.
 */

public abstract class BaseCrudRestControllerAbstract<M extends BaseModel<I>, C extends BaseCriteria<I>, D, S extends BaseService<M, C, D, I, U>, I extends Comparable<? super I>, U> implements ReadRestController<M, C, S, I, U>, WriteRestController<M, D, S, I, U> {
    protected final Logger LOGGER = LoggerFactory.getLogger(this.getClass());

    protected final S service;

    protected BaseCrudRestControllerAbstract(S service) {
        this.service = service;
    }

    public S getService() {
        return service;
    }

    public abstract U getUser();
}

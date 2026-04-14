package com.hmdp.utils;

public interface ILock {

    /**
     * 设置锁  超时自动取消
     * @param timeoutSec
     * @return
     */
    boolean tryLock(long timeoutSec);

    void unLock();
}

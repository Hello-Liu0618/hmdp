package com.hmdp.utils;

public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，超过时间自动释放
     * @return true 代表获取锁成功, false 代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    void unlock();
}

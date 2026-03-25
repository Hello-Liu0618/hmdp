package com.hmdp;

import cn.hutool.cron.task.RunnableTask;
import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Test
    public void test() {
        Shop shop = shopService.getById(1);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1, shop, 10L, TimeUnit.SECONDS);
    }

    private ExecutorService executorService = Executors.newFixedThreadPool(500);

    @Test
    void tsetIdWorker() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable runnableTask = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("test");
                System.out.println("id = " + id);
            }
            countDownLatch.countDown();
        };
        long start = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            executorService.submit(runnableTask);
            //System.out.println(i);
        }
        //System.out.println(countDownLatch.getCount());
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - start));
    }
}

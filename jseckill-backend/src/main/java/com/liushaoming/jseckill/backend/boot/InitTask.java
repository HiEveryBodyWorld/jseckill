package com.liushaoming.jseckill.backend.boot;

import com.liushaoming.jseckill.backend.constant.RedisKey;
import com.liushaoming.jseckill.backend.constant.RedisKeyPrefix;
import com.liushaoming.jseckill.backend.dao.SeckillDAO;
import com.liushaoming.jseckill.backend.entity.Seckill;
import com.liushaoming.jseckill.backend.mq.MQConsumer;
import com.liushaoming.jseckill.backend.singleton.MyRuntimeSchema;
import io.protostuff.LinkedBuffer;
import io.protostuff.ProtostuffIOUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;
import java.util.List;

@Component
public class InitTask implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(InitTask.class);
å
    @Resource(name = "initJedisPool")
    private JedisPool jedisPool;
    @Resource
    private SeckillDAO seckillDAO;
    @Resource
    private MQConsumer mqConsumer;

    @Override
    public void run(String... args) throws Exception {
        initRedis();
        logger.info("StartToConsumeMsg--->");
        mqConsumer.receive();
    }

    /**
     * 预热秒杀数据到Redis
     */
    private void initRedis() {
        Jedis jedis = jedisPool.getResource();
        //清空Redis缓存
        jedis.flushDB();

        List<Seckill> seckillList = seckillDAO.queryAll(0, 10);
        if (seckillList == null || seckillList.size()< 1) {
            logger.info("--FatalError!!! seckill_list_data is empty");
            return;
        }

        for (Seckill seckill : seckillList) {
            jedis.sadd(RedisKey.SECKILL_ID_SET, seckill.getSeckillId() + "");

            String inventoryKey = RedisKeyPrefix.SECKILL_INVENTORY + seckill.getSeckillId();
            List<Integer> inventoryKeys = new ArrayList<>();
            for(int i=1;i<seckill.getInventory();i++){
                inventoryKeys.add(i);
            }
              /**
             * 防止高并发超卖问题,将秒杀产品的库存存入list，pop成功则减库存
             */
            jedis.rpush(inventoryKey, String.valueOf(inventoryKeys));
            jedis.set(inventoryKey, String.valueOf(seckill.getInventory()));

            String seckillGoodsKey = RedisKeyPrefix.SECKILL_GOODS + seckill.getSeckillId();
            byte[] goodsBytes = ProtostuffIOUtil.toByteArray(seckill, MyRuntimeSchema.getInstance().getGoodsRuntimeSchema(),
                    LinkedBuffer.allocate(LinkedBuffer.DEFAULT_BUFFER_SIZE));
            jedis.set(seckillGoodsKey.getBytes(), goodsBytes);
        }
        jedis.close();
        logger.info("Redis缓存数据初始化完毕！");
    }
}

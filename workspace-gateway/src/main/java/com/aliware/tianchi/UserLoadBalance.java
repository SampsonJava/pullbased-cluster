package com.aliware.tianchi;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 负载均衡扩展接口
 * 必选接口，核心接口
 * 此类可以修改实现，不可以移动类或者修改包名
 * 选手需要基于此类实现自己的负载均衡算法
 */
public class UserLoadBalance implements LoadBalance {
    private final static Logger logger = LoggerFactory.getLogger(UserLoadBalance.class);

    @Override
    public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) throws RpcException {
        long[] serviceWeight = new long[invokers.size()];
        long totalWeight = 0;
        long weight;
        for (int index = 0, size = invokers.size(); index < size; ++index) {
            Invoker<T> invoker = invokers.get(index);
            //需要乘以成功的平均值
            weight = NodeManager.state(invoker).getWeight();
            serviceWeight[index] = weight;
            totalWeight += weight;
        }
        long expect = ThreadLocalRandom.current().nextLong(totalWeight);
        logger.info("totalweight:{}, expect:{}, serviceWeight:{}", totalWeight, expect, Arrays.toString(serviceWeight));
        for (int i = 0, size = invokers.size(); i < size; ++i) {
            expect -= serviceWeight[i];
            if (expect < 0) {
                return invokers.get(i);
            }
        }
        return invokers.get(ThreadLocalRandom.current().nextInt(invokers.size()));
    }
}

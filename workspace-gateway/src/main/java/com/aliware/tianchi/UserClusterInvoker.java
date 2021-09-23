package com.aliware.tianchi;

import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.timer.Timeout;
import org.apache.dubbo.common.timer.Timer;
import org.apache.dubbo.common.timer.TimerTask;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.cluster.Directory;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.cluster.support.AbstractClusterInvoker;
import org.apache.dubbo.rpc.protocol.dubbo.FutureAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_TIMEOUT;
import static org.apache.dubbo.common.constants.CommonConstants.TIMEOUT_KEY;

/**
 * 集群实现
 * 必选接口，核心接口
 * 此类可以修改实现，不可以移动类或者修改包名
 * 选手需要基于此类实现自己的集群调度算法
 */
public class UserClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private final Timer checker;
    private static final long delay = 50;
    private static AppResponse EMPTY = new AppResponse();

    public UserClusterInvoker(Directory<T> directory) {
        super(directory);
        checker = new HashedWheelTimer(
                new NamedThreadFactory("user-cluster-check-timer", true),
                30, TimeUnit.MILLISECONDS, 40);
    }

    @Override
    protected Result doInvoke(Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        Invoker<T> invoker = this.select(loadbalance, invocation, invokers, null);
        Result result = invoker.invoke(invocation);
        if (result instanceof AsyncRpcResult) {
            AsyncRpcResult asyncRpcResult = (AsyncRpcResult) result;
            CompletableFuture<AppResponse> responseFuture = asyncRpcResult.getResponseFuture();
            if (!responseFuture.isDone()) {
                OnceCompletableFuture onceCompletableFuture = new OnceCompletableFuture(responseFuture);
                AsyncRpcResult rpcResult = new AsyncRpcResult(onceCompletableFuture, invocation);
                RpcContext.getServiceContext().setFuture(new FutureAdapter<>(onceCompletableFuture));
                checker.newTimeout(new FutureTimeoutTask(loadbalance, invocation, rpcResult, onceCompletableFuture, invoker, invokers),
                        delay, TimeUnit.MILLISECONDS);
                return rpcResult;
            }
        }
        return result;
    }

    @Override
    public void destroy() {
        super.destroy();
        checker.stop();
    }

    class FutureTimeoutTask implements TimerTask {
        Invoker<T> invoker;
        List<Invoker<T>> invokers;
        AsyncRpcResult asyncRpcResult;
        LoadBalance loadbalance;
        Invocation invocation;
        final long time;
        OnceCompletableFuture onceCompletableFuture;
        long start;

        public FutureTimeoutTask(LoadBalance loadbalance, Invocation invocation, AsyncRpcResult asyncRpcResult,
                                 OnceCompletableFuture onceCompletableFuture, Invoker<T> invoker, List<Invoker<T>> invokers) {
            this.asyncRpcResult = asyncRpcResult;
            this.onceCompletableFuture = onceCompletableFuture;
            this.invoker = invoker;
            this.invokers = invokers;
            this.loadbalance = loadbalance;
            this.invocation = invocation;
            time = invoker.getUrl().getPositiveParameter(TIMEOUT_KEY, DEFAULT_TIMEOUT);
            start = System.currentTimeMillis();
        }

        @Override
        public void run(Timeout timeout) throws Exception {
            if (onceCompletableFuture == null || (onceCompletableFuture.isDone() && !asyncRpcResult.hasException())) {
                return;
            }
            if (System.currentTimeMillis() - start > time) {
                onceCompletableFuture.complete(new AppResponse(new RpcException(RpcException.TIMEOUT_EXCEPTION, "Invoke remote method timeout. method: " +
                        invocation.getMethodName() + ", provider: " + getUrl())));
                return;
            }
            NodeState state = NodeManager.state(invoker);
            state.addTimeout(time);
            ArrayList<Invoker<T>> newInvokers = new ArrayList<>(this.invokers);
            newInvokers.remove(invoker);
            invoker = select(loadbalance, invocation, newInvokers, null);
            Result result = invoker.invoke(invocation);
            //同样将结果放置到这里
            if (onceCompletableFuture.replace(((AsyncRpcResult) result).getResponseFuture())) {
                timeout.timer().newTimeout(timeout.task(), delay, TimeUnit.MILLISECONDS);
            }
        }
    }

    static class OnceCompletableFuture extends CompletableFuture<AppResponse> {
        CompletableFuture<AppResponse> responseFuture;

        public OnceCompletableFuture(CompletableFuture<AppResponse> responseFuture) {
            register(responseFuture);
        }

        private void register(CompletableFuture<AppResponse> responseFuture) {
            this.responseFuture = responseFuture;
            this.responseFuture.whenComplete((appResponse, throwable) -> {
                if (null != appResponse && !appResponse.hasException()) {
                    OnceCompletableFuture.this.complete(appResponse);
                }
            });
        }

        public boolean replace(CompletableFuture<AppResponse> responseFuture) {
            if (this.isDone()) {
                return false;
            }
            register(responseFuture);
            return true;
        }
    }
}

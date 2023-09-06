package com.sdu.irpc.framework.core.handler.inbound;

import com.sdu.irpc.framework.common.entity.holder.ShutdownContextHolder;
import com.sdu.irpc.framework.common.entity.rpc.RequestPayload;
import com.sdu.irpc.framework.common.entity.rpc.RpcRequest;
import com.sdu.irpc.framework.common.entity.rpc.RpcResponse;
import com.sdu.irpc.framework.common.enums.RespCode;
import com.sdu.irpc.framework.core.config.IRpcBootstrap;
import com.sdu.irpc.framework.core.config.ServiceConfig;
import com.sdu.irpc.framework.core.protection.Limiter;
import com.sdu.irpc.framework.core.protection.TokenBucketRateLimiter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.util.Map;

@Slf4j
public class MethodInvokeHandler extends SimpleChannelInboundHandler<RpcRequest> {

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, RpcRequest request) {
        log.info("收到数据: {}", request.toString());
        // 封装响应体
        RpcResponse response = new RpcResponse();
        response.setRequestId(request.getRequestId());
        response.setCompressionType(request.getCompressionType());
        response.setSerializationType(request.getSerializationType());
        // 获得通道
        Channel channel = channelHandlerContext.channel();
        // 查看关闭的挡板是否打开，如果挡板已经打开，返回一个错误的响应
        if (ShutdownContextHolder.BAFFLE.get()) {
            response.setCode(RespCode.CLOSING.getCode());
            channel.writeAndFlush(response);
            return;
        }
        ShutdownContextHolder.REQUEST_COUNTER.increment();
        // 请求限流
        SocketAddress socketAddress = channel.remoteAddress();
        Map<SocketAddress, Limiter> ipRateLimiter = IRpcBootstrap.getInstance().getConfiguration().getIpRateLimiter();
        Limiter limiter = ipRateLimiter.get(socketAddress);
        if (null == limiter) {
            limiter = new TokenBucketRateLimiter(10, 1000);
            ipRateLimiter.put(socketAddress, limiter);
        }
        boolean allowRequest = limiter.allowRequest();
        if (!allowRequest) {
            response.setCode(RespCode.RATE_LIMIT.getCode());
        } else {
            // 拿到真正的payload
            RequestPayload requestPayload = request.getRequestPayload();
            try {
                Object result = doInvoke(requestPayload);
                log.info("请求【{}】已经在服务端完成方法调用。", request.getRequestId());
                response.setCode(RespCode.SUCCESS.getCode());
                response.setBody(result);
                log.info("服务提供方响应体: {}", response);
            } catch (Exception e) {
                log.error("Id为【{}】的请求在调用过程中发生异常。", response.getRequestId(), e);
                response.setCode(RespCode.FAIL.getCode());
            }
        }
        // 设置响应时间戳 写回响应
        response.setTimeStamp(System.currentTimeMillis());
        channel.writeAndFlush(response);
        ShutdownContextHolder.REQUEST_COUNTER.decrement();
    }

    private Object doInvoke(RequestPayload requestPayload) {
        String path = requestPayload.getPath();
        String methodName = requestPayload.getMethodName();
        Object[] parametersValue = requestPayload.getParametersValue();

        // 寻找服务的具体实现
        ServiceConfig serviceConfig = IRpcBootstrap.SERVICE_MAP.get(path);
        Object referenceImpl = serviceConfig.getReference();
        Method method = serviceConfig.getMethod();
        // 获取方法对象 通过反射调用invoke方法
        Object returnValue;
        try {
            returnValue = method.invoke(referenceImpl, parametersValue);
        } catch (InvocationTargetException | IllegalAccessException e) {
            log.error("调用服务【{}】的方法【{}】时发生了异常。", path, methodName, e);
            throw new RuntimeException(e);
        }
        return returnValue;
    }
}

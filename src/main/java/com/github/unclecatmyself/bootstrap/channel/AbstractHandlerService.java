package com.github.unclecatmyself.bootstrap.channel;

import com.alibaba.fastjson.JSONArray;
import com.github.unclecatmyself.bootstrap.channel.http.HttpChannelImpl;
import com.github.unclecatmyself.bootstrap.channel.protocol.HttpChannel;
import com.github.unclecatmyself.bootstrap.channel.protocol.InChatVerifyService;
import com.github.unclecatmyself.bootstrap.channel.protocol.Response;
import com.github.unclecatmyself.bootstrap.channel.protocol.SocketChannel;
import com.github.unclecatmyself.bootstrap.channel.ws.WebSocketChannel;
import com.github.unclecatmyself.bootstrap.handler.InChatMessageHandler;
import com.github.unclecatmyself.core.bean.InChatMessage;
import com.github.unclecatmyself.core.bean.SendInChat;
import com.github.unclecatmyself.core.bean.vo.SendServerVO;
import com.github.unclecatmyself.core.constant.Constants;
import com.github.unclecatmyself.core.constant.LogConstant;
import com.github.unclecatmyself.core.constant.StateConstant;
import com.github.unclecatmyself.core.utils.DateUtil;
import com.github.unclecatmyself.core.utils.DateUtils;
import com.github.unclecatmyself.scheduling.AsyncListener;
import com.github.unclecatmyself.support.HandlerService;
import com.github.unclecatmyself.core.bean.InChatResponse;
import com.github.unclecatmyself.support.MessageFactory;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by MySelf on 2018/11/21.
 */
public class AbstractHandlerService extends HandlerService {

    private final static Logger logger = LoggerFactory.getLogger(AbstractHandlerService.class);

    private final InChatVerifyService inChatVerifyService;

    private final Response response = new InChatResponse();

    private final HttpChannel httpChannel = new HttpChannelImpl();

    private final SocketChannel webSocketChannel = new WebSocketChannel();

    private AsyncListener asyncListener;

    public AbstractHandlerService(InChatVerifyService inChatVerifyService, AsyncListener asyncListener) {
        this.inChatVerifyService = inChatVerifyService;
        this.asyncListener = asyncListener;
        registerServiceToProtocolMessageFactory();
    }


    @Override
    public void getList(Channel channel) {
        httpChannel.getList(channel);
    }

    @Override
    public void getSize(Channel channel) {
        httpChannel.getSize(channel);
    }

    @Override
    public void getState(Channel channel, SendServerVO sendServerVO) {
        httpChannel.getState(channel, sendServerVO);
    }

    @Override
    public void sendFromServer(Channel channel, SendServerVO serverVO) {
        httpChannel.sendFromServer(channel, serverVO);
    }

    @Override
    public void sendInChat(Channel channel, FullHttpMessage msg) {
        System.out.println(msg);
        String content = msg.content().toString(CharsetUtil.UTF_8);
        Gson gson = new Gson();
        SendInChat sendInChat = gson.fromJson(content, SendInChat.class);
        httpChannel.sendByInChat(channel, sendInChat);
    }

    @Override
    public void notFindUri(Channel channel) {
        httpChannel.notFindUri(channel);
    }

    @InChatMessageHandler
    @Override
    public boolean login(Channel channel, InChatMessage message) {
        logger.info(LogConstant.DEFAULTWEBSOCKETHANDLER_LOGIN);
        //校验规则，自定义校验规则
        return check(channel, message);
    }

    @InChatMessageHandler
    @Override
    public void sendMeText(Channel channel, InChatMessage message) {
        logger.info(LogConstant.DEFAULTWEBSOCKETHANDLER_SENDME);
        Gson gson = new Gson();
        channel.writeAndFlush(new TextWebSocketFrame(
                gson.toJson(response.sendMe(message.getValue()))));
        message.setOnline(Constants.TRUE);
        asyncListener.asyncData(message);
    }

    @InChatMessageHandler
    @Override
    public void sendToText(Channel channel, InChatMessage message) {
        logger.info(LogConstant.DefaultWebSocketHandler_SENDTO);
        Gson gson = new Gson();
        String otherOne = message.getOne();
        String value = message.getValue();
        String token = message.getToken();
        //返回给自己
        channel.writeAndFlush(new TextWebSocketFrame(
                gson.toJson(response.sendBack(otherOne, value))));
        if (webSocketChannel.hasOther(otherOne)) {
            //发送给对方--在线
            Channel other = webSocketChannel.getChannel(otherOne);
            InChatResponse chatResponse = response.getMessage(token, value);
            chatResponse.setMsgtime(DateUtils.getTime());
            if (other == null) {
                //转http分布式
                httpChannel.sendInChat(otherOne, chatResponse);
            } else {
                other.writeAndFlush(new TextWebSocketFrame(
                        gson.toJson(chatResponse)));
                message.setOnline(Constants.TRUE);
            }
        } else {
            message.setOnline(Constants.FALSE);
        }
        asyncListener.asyncData(message);
    }

    @InChatMessageHandler
    @Override
    public void sendGroupText(Channel channel, InChatMessage message) {
        logger.info(LogConstant.DEFAULTWEBSOCKETHANDLER_SENDGROUP);
        Gson gson = new Gson();
        String groupId = message.getGroupId();
        String token = message.getToken();
        String value = message.getValue();
        String no_online = "";
        JSONArray array = inChatVerifyService.getArrayByGroupId(groupId);
        channel.writeAndFlush(new TextWebSocketFrame(
                gson.toJson(response.sendGroup(token, value, groupId))));
        for (Object item : array) {
            if (!token.equals(item)) {
                if (webSocketChannel.hasOther((String) item)) {
                    Channel other = webSocketChannel.getChannel((String) item);
                    if (other == null) {
                        //转http分布式
                        httpChannel.sendInChat((String) item, response.sendGroup(token, value, groupId));
                    } else {
                        other.writeAndFlush(new TextWebSocketFrame(
                                gson.toJson(response.sendGroup(token, value, groupId))));
                    }
                } else {
                    no_online = item + "、" + no_online;
                }
            }
        }
        message.setOnlineGroup(no_online.substring(0, no_online.length() - 1));
        asyncListener.asyncData(message);
    }

    @InChatMessageHandler
    @Override
    public void verify(Channel channel, InChatMessage message) {
        Gson gson = new Gson();
        String token = message.getToken();
        System.out.println(token);
        if (inChatVerifyService.verifyToken(token)) {
            return;
        } else {
            channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(response.loginError())));
            close(channel);
        }
    }

    @InChatMessageHandler
    @Override
    public void sendPhotoToMe(Channel channel, InChatMessage message) {
        logger.info("图片到个人");
        Gson gson = new Gson();
        System.out.println(message.getValue());
        channel.writeAndFlush(new TextWebSocketFrame(
                gson.toJson(response.sendMe(message.getValue()))));
        asyncListener.asyncData(message);
    }

    private Boolean check(Channel channel, InChatMessage message) {
        Gson gson = new Gson();
        String token = message.getToken();
        if (inChatVerifyService.verifyToken(token)) {
            channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(response.loginSuccess())));
            webSocketChannel.loginWsSuccess(channel, token);
            asyncListener.asyncState(StateConstant.ONLINE, token);
            return true;
        }
        channel.writeAndFlush(new TextWebSocketFrame(gson.toJson(response.loginError())));
        close(channel);
        return false;
    }

    @Override
    public void close(Channel channel) {
        String token = webSocketChannel.close(channel);
        asyncListener.asyncState(StateConstant.OFFLINE, token);
    }

    /**
     * 注册服务到协议处理器
     */
    private void registerServiceToProtocolMessageFactory() {
        try {
            logger.warn("注册服务到协议处理器 begin");
            MessageFactory.getInstance().registerService(this);
            logger.warn("注册服务到协议处理器 end");
        } catch (Exception e) {
            logger.error("注册协议异常:", e);
            System.exit(1);
        }
    }
}

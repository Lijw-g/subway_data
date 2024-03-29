package com.subwaydata.subway.handler;

import com.subwaydata.subway.kafka.KafkaSender;
import com.subwaydata.subway.util.CRC16Util;
import com.subwaydata.subway.util.DataUtil;
import com.subwaydata.subway.util.DateUtil;
import com.subwaydata.subway.util.HexUtil;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author: Lijiwen
 * Description:
 * @createDate
 **/
@Component
public class DataHandler extends IoHandlerAdapter {
    @Autowired
    private KafkaSender<String> kafkaSenders;
    public static Logger logger = Logger.getLogger(DataHandler.class.getName());
    /**
     * messageSent是Server响应给Clinet成功后触发的事件
     */
    @Override
    public void messageSent(IoSession session, Object message) throws Exception {
        if (message instanceof IoBuffer) {
            IoBuffer buffer = (IoBuffer) message;
            byte[] bb = buffer.array();
            for (int i = 0; i < bb.length; i++) {
                System.out.print((char) bb[i]);
            }
        }
    }

//抛出异常触发的事件

    @Override
    public void exceptionCaught(IoSession session, Throwable cause)
            throws Exception {
        cause.printStackTrace();
        session.close(true);
    }

//Server接收到UDP请求触发的事件

    @Override
    public void messageReceived(IoSession session, Object message) throws Exception {
        System.out.println("messageReceived");
        if (message instanceof IoBuffer) {
            IoBuffer buffer = (IoBuffer) message;
            byte[] datas = buffer.array();
            String data = HexUtil.bytes2HexString(datas);
            String order = data.substring(22, 26);
            Integer dataSize = DataUtil.getDataSize((data.substring(4, 8)));
            data = data.substring(0, dataSize * 2);
            String clientIP = ((InetSocketAddress) session.getRemoteAddress()).getAddress().getHostAddress();
            //数据包
            if ("720E".equals(order)) {
                String crcCode = CRC16Util.creatCrc16_s(data.substring(0, data.length() - 8));
                String dataCrc = data.substring(data.length() - 8, data.length() - 4);
                StringBuilder responsData = new StringBuilder();
                responsData.append("EA6A12002306010226FFFF720E");
                if (crcCode.equals(dataCrc)) {
                    kafkaSenders.sendWithTopic(data, "core_data_topic");
                    responsData.append("01");
                    responsData.append(CRC16Util.creatCrc16_s(responsData.toString()));
                    responsData.append("0D0A");
                    byte[] data2 = DataUtil.creatDate(responsData.toString());
                    //3.响应客户端
                    //返回信息给Clinet端
                    IoBuffer buffer1 = IoBuffer.wrap(data2);
                    session.write(buffer1);
                    logger.info("向传感器响应的数据是" + responsData);
                } else {
                    responsData.append("00");
                    responsData.append(CRC16Util.creatCrc16_s(responsData.toString()));
                    logger.warning("数据校验失败，数据不正常");
                }
            }
            //心跳包
            if ("740C".equals(order)) {
                //TODO 协议、长度、crc16校验
                //3.响应客户端
                String diviceStatus = data.substring(26, 28);
                String ip = clientIP;
                List<String> heart = new ArrayList<>();
                heart.add(ip);
                heart.add(diviceStatus);
                heart.add(data.substring(8, 22));
                kafkaSenders.sendWithTopic(heart,"heart_topic");
                if ("00".equals(diviceStatus)) {
                    logger.info("工作状态正常");
                } else if ("E1".equals(diviceStatus)) {
                    logger.info("采集终端异常");
                } else if ("E2".equals(diviceStatus)) {
                    logger.info("电压传感器异常");
                } else if ("E3".equals(diviceStatus)) {
                    logger.info("电流传感器异常");
                } else if("E4".equals(diviceStatus)){
                    logger.info("开合度传感器异常");
                } else if("FF".equals(diviceStatus)){
                    logger.info("未知异常");
                }
                else {
                    logger.info("未知异常数据异常");
                }
                /***
                 年( 2 字 节 )
                 前低后高，比如 2019 年表示为 E3 07
                 月( 1 字 节 )
                 比如 12 月表示为 0C
                 日( 1 字 节 )
                 比如 30 日表示为 1E
                 时( 1 字 节 )
                 比如 23 时表示为 17
                 分( 1 字 节 )
                 比如 59 分表示为 3B
                 秒( 1 字 节 )
                 比如 59 秒表示为 3B*/
                StringBuilder answerSb = new StringBuilder();
                answerSb.append("EA6A18002306010226FFFF74");
                Calendar now = Calendar.getInstance();
                answerSb.append(DateUtil.getYearToHex(String.valueOf(now.get(Calendar.YEAR))));
                answerSb.append(DateUtil.getOther(now.get(Calendar.MONTH) + 1));
                answerSb.append(DateUtil.getOther(now.get(Calendar.DAY_OF_MONTH)));
                answerSb.append(DateUtil.getOther(now.get(Calendar.HOUR_OF_DAY)));
                answerSb.append(DateUtil.getOther(now.get(Calendar.MINUTE)));
                answerSb.append(DateUtil.getOther(now.get(Calendar.SECOND)));
                answerSb.append(CRC16Util.creatCrc16_s(answerSb.toString()));
                answerSb.append("0A0D");
                byte[] answer = DataUtil.creatDate(answerSb.toString().toUpperCase());
                IoBuffer buffer1 = IoBuffer.wrap(answer);
                session.write(buffer1);
                logger.info("心跳向传感器响应的数据是" + answerSb.toString());
            }
            //解析采集终端上传信息读取当前采集终端编码 0x7211
           else if ("7211".equals(order)) {
                StringBuilder resString = new StringBuilder();
                resString.append("EA6A11002306010226FFFF71110");
                resString.append(CRC16Util.creatCrc16_s(resString.toString()));
                resString.append("0D0A");
                byte[] respMsg = DataUtil.creatDate(resString.toString());
                IoBuffer buffer1 = IoBuffer.wrap(respMsg);
                session.write(buffer1);
                logger.info("发送给终端读取终端编码命令为" + respMsg);
                logger.info("读取到的信息为" + data);
                logger.info("接受到的编码为" + data.substring(26, 54));
                //0x23 0x06 0x01 0x02 0x26 0xFF 0xFF (不足 14 位、用“F”补齐，预留)，
                // 表示为广东省 广州市1号线第2号列车第2节车厢6号门
                String codeInfo = data.substring(26, 54);
                String pro = codeInfo.substring(0, 2);
                logger.info("省份代码" + pro);
                String cityId = codeInfo.substring(2, 4);
                logger.info("城市id" + cityId);
                String line = codeInfo.substring(4, 6);
                logger.info("地铁线" + line);
                String train = codeInfo.substring(6, 8);
                logger.info("列车号" + train);
                int part = Integer.valueOf(codeInfo.substring(8, 10));
                logger.info("第" + (part / 10) + "节车厢");
                logger.info((part % 10) + "号门");


            }
            //设置采集终端时间间隔 0x7212
           else if ("7212".equals(order)) {
                String msg = "EA6A11002306010226FFFF711200000D0A";
                byte[] msgs = DataUtil.creatDate(msg);
                IoBuffer buffer1 = IoBuffer.wrap(msgs);
                session.write(buffer1);
                logger.info("发送给终端设置终端发送间隔时间的命令为：" + msg);
                logger.info("读取到的信息为" + data);
                logger.info("接受到的时间间隔" + Long.parseLong(data.substring(26, 28), 16) + "s");
            }
           else {
               kafkaSenders.sendWithTopic(data,"geographical_topic");
            }

        }
    }

    /***
     * @author: Lijiwen
     * Description:连接关闭触发的事件
     * @param session
     * @return void
     * @createDate
     **/
    @Override
    public void sessionClosed(IoSession session) {
        System.out.println("Session closed...");
    }

    /**
     * @param session
     * @return void
     * @author: Lijiwen
     * Description:建立连接触发的事件
     * @createDate
     **/
    @Override
    public void sessionCreated(IoSession session) {
        System.out.println("Session created...");
        SocketAddress remoteAddress = session.getRemoteAddress();
        String clientIP = ((InetSocketAddress) session.getRemoteAddress()).getAddress().getHostAddress();
        session.setAttribute("KEY_SESSION_CLIENT_IP", clientIP);
        System.out.println("remoteAddress：" + remoteAddress);

    }

    /**
     * @param session
     * @param status
     * @return void
     * @author: Lijiwen
     * Description:会话空闲
     * @createDate
     **/
    @Override
    public void sessionIdle(IoSession session, IdleStatus status) {
        System.out.println("Session idle...");
    }

    /**
     * 打开连接触发的事件，它与sessionCreated的区别在于，
     * 一个连接地址（A）第一次请求Server会建立一个Session默认超时时间为1分钟，
     * 此时若未达到超时时间这个连接地址（A）再一次向Server发送请求即是sessionOpened
     * （连接地址（A）第一次向Server发送请求或者连接超时后向Server发送请求时会同时触发
     * sessionCreated和sessionOpened两个事件）
     */
    @Override
    public void sessionOpened(IoSession session) {
        System.out.println("Session Opened...");
        SocketAddress remoteAddress = session.getRemoteAddress();
        System.out.println(remoteAddress);
    }
}
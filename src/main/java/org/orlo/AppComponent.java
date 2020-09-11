/*
 * Copyright 2020 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package apps.myonos.src.main.java.org.orlo;

import com.eclipsesource.json.JsonArray;
import com.eclipsesource.json.JsonObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.onosproject.net.Device;
import org.onosproject.net.device.PortStatistics;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.statistic.PortStatisticsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.VlanId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleOperations;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.Topology;
import org.onosproject.net.topology.TopologyEdge;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private final  Logger log = LoggerFactory.getLogger(getClass());
    private ApplicationId appId;
    private HashMap<String, PortNumber> testPortMap = HandleConfigFile.getPortMap();
    private HashMap<String, DeviceId> testSwMap = HandleConfigFile.getSwMap();
    private HashMap<DeviceId, String> reverSwMap = HandleConfigFile.getReverswMap();
    private HashMap<String, IpPrefix> testHostIpMap = HandleConfigFile.getHostIpMap();
    private HashMap<String, MacAddress> testHostMacMap = HandleConfigFile.getHostMacMap();
    private HashMap<String, DeviceId> testip2swMap = HandleConfigFile.getIp2swMap();
    private int switchNum = HandleConfigFile.getSwithNum();
    private HashMap<String, PortNumber> stringPortNumberHashMap = new HashMap<>();
    private int flowTableCnt = 0;
    private int timesCnt = 0;

    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected CoreService coreService;
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected PacketService packetService;
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected HostService hostService;
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected EdgePortService edgePortService;
    @Reference(cardinality = org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY)
    protected PortStatisticsService portStatisticsService;

    private ExecutorService executorService = Executors.newFixedThreadPool(6);
    private Timer timer = new Timer();
    private HashMap<String, HashMap<String, Long>> matrixMapStore = new HashMap<>();
    private HashMap<String, HashMap<String, Long>> matrixInfoStore = new HashMap<>();
    private  ConcurrentLinkedQueue<String> routingClq = new ConcurrentLinkedQueue<>();
    private  ConcurrentLinkedQueue<String> flowClq = new ConcurrentLinkedQueue<>();
    private String topoIdx = "{ \"topo_idx\" : 0}*";
    private Thread defaultFlowThread;
    private Thread hostThread;
    private Thread installRoutingThread;
    private Thread installFlowThread;
    private Thread topoIdxThread;
    private ArrayList<FlowRule> flowRulesList = new ArrayList<>();
    private ArrayList<FlowRule> beforeflowRulesList = new ArrayList<>();
    private ArrayList<FlowRule> optiflowRulesList = new ArrayList<>();
    private ArrayList<FlowRule> beforeoptiflowRulesList = new ArrayList<>();
    private HashMap<String, ArrayList<Long>> oldStatics = new HashMap<>();
    private ArrayList<TopologyEdge> edgeArrayList = new ArrayList<>();
    @Activate
    protected void activate() {

        appId = coreService.registerApplication("org.orlo.app");
        log.info("------------Activate Started-------------------------");
        initMethod();
        //请求默认路由
        executorService.submit(new DefaultFlowThread());
        topoIdxThread = new Thread(new TopoIdxThread());
        topoIdxThread.start();
        //开启获取host信息的线程,并且配置table0流表信息会添加到线程安全队列中
        hostThread = new Thread(new HostModuleThread(flowClq));
        hostThread.start();
        //开启下发优化路由的线程
        installRoutingThread = new Thread(new RoutingFlowThread());
        installRoutingThread.start();
        //开启安装入口交换机table0流表项的线程
        installFlowThread = new Thread(new InstallFlowByClqThread());
        installFlowThread.start();
//        showEdgeSpeed(30);
        log.info("----------------Activated end-------------------------");
    }

    @Deactivate
    protected void deactivate() {
        executorService.shutdown();
        timer.cancel();
        hostThread.stop();
        installRoutingThread.stop();
        installFlowThread.stop();
        topoIdxThread.stop();
        log.info("--------------------System Stopped-------------------------");
    }
    /**
     * 初始化系统启动所需的相关方法.
     */
    private void initMethod() {
        //设置相邻switch间的端口信息
        setPortInfo();
        //初始化流量矩阵Map
        initMatrixMapStore();
        //初始化
        initOldStatics();
        //安装与host直连switch的默认路由
        installHostToSW();
        //安装table1与table2的默认流表项
        installTcpTable();
        //安装IP到table2的流表项
        installIP2Table2();
        //每隔5s上传一次流量矩阵
//        storeMatrixMission();
        //每隔1s统计一次接入端口流数据
        storeFlowRateMission();
    }
    /**
     * 设置switch间port的信息.
     */
    private void setPortInfo() {
        if (stringPortNumberHashMap != null) {
            stringPortNumberHashMap.clear();
        }
        Topology topology = topologyService.currentTopology();
        TopologyGraph graph = topologyService.getGraph(topology);
        Set<TopologyEdge> edges = graph.getEdges();
        for (TopologyEdge edge : edges) {
            ConnectPoint src = edge.link().src();
            ConnectPoint dst = edge.link().dst();
            String s1 = src.deviceId().toString() + '-' + dst.deviceId().toString();
            stringPortNumberHashMap.put(s1, src.port());
        }
        log.info("------port info set complete!-----");
    }
    /**
     * 初始化存储流量矩阵的Map.
     */
    private void initMatrixMapStore() {
        for (int i = 0; i < switchNum; i++) {
            HashMap<String, Long> map = new HashMap<>();
            for (int j = 1; j < switchNum * 3 + 1; j++) {
                map.put("" + j, 0L);
            }
            matrixMapStore.put(String.valueOf(i), map);
            matrixInfoStore.put(String.valueOf(i), map);
        }

    }
    /**
     * 初始化oldStatics Map.
     */
    private void initOldStatics() {
        Set<String> keySet = testSwMap.keySet();
        for (String key : keySet) {
            ArrayList<Long> longArrayList = new ArrayList<>();
            longArrayList.add(0L);
            longArrayList.add(0L);
            oldStatics.put(key, longArrayList);
        }
    }
    /**
     * 安装与host相连接switch到host的默认路由,安装在了table0中,优先级最高.
     * 即当目的IP匹配到是交换机所直接连接的host,则直接将数据包交给host.
     */
    private void installHostToSW() {
        for (String s : testHostMacMap.keySet()) {
            IpPrefix ipPrefix = testHostIpMap.get(s);
            DeviceId deviceId = testSwMap.get(s);
            PortNumber port = testPortMap.get(s);
            TrafficSelector.Builder selectBuilder = DefaultTrafficSelector.builder();
            TrafficTreatment.Builder trafficBuilder = DefaultTrafficTreatment.builder();
            selectBuilder.matchEthType(Ethernet.TYPE_IPV4)
                    .matchIPDst(ipPrefix);
            trafficBuilder.setOutput(port);
            DefaultForwardingObjective.Builder objBuilder = DefaultForwardingObjective.builder();
            objBuilder.withSelector(selectBuilder.build())
                    .withTreatment(trafficBuilder.build())
                    .withPriority(60000)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .fromApp(appId)
                    .makePermanent();
            flowObjectiveService.apply(deviceId, objBuilder.add());
        }
        log.info("----------HostToSW complete----------------");
    }
    /**
     * 配置所有switch的table1.
     * table1为统计流量矩阵的流表
     * table2中没有匹配到的包就丢弃
     */
    private void installTcpTable() {
        Set<String> keySet = testSwMap.keySet();
        for (String key : keySet) {
            DeviceId deviceId = testSwMap.get(key);
            IpPrefix srcIp = testHostIpMap.get(key);
            ArrayList<String> arrayList = new ArrayList<>();
            // 添加数量为switchNum的数字字符
            for (int i = 0; i < switchNum; i++) {
                arrayList.add(String.valueOf(i));
            }
            HashSet<String> hostSet = new HashSet<>(arrayList);
            hostSet.remove(key);
            for (String dst : hostSet) {
                IpPrefix dstIp = testHostIpMap.get(dst);
                //安装table1中的测量流表
                installFlowTable1(srcIp, dstIp, "1", deviceId);
                installFlowTable1(srcIp, dstIp, "2", deviceId);
                installFlowTable1(srcIp, dstIp, "3", deviceId);
            }
            //配置table2中丢包的流表.
            tcpToDrop(deviceId);
        }
    }
    /**
     *安装IP到table2的流表项.
     * 在table0中安装直接到table2的流表项
     */
    private void installIP2Table2() {
        for (String s : testHostMacMap.keySet()) {
            DeviceId deviceId = testSwMap.get(s);
            TrafficSelector.Builder selectBuilder = DefaultTrafficSelector.builder();
            TrafficTreatment.Builder trafficBuilder = DefaultTrafficTreatment.builder();
            selectBuilder.matchEthType(Ethernet.TYPE_IPV4);
            trafficBuilder.transition(2);
            DefaultForwardingObjective.Builder objBuilder = DefaultForwardingObjective.builder();
            objBuilder.withSelector(selectBuilder.build())
                    .withTreatment(trafficBuilder.build())
                    .withPriority(50000)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .fromApp(appId)
                    .makePermanent();
            flowObjectiveService.apply(deviceId, objBuilder.add());
        }
        log.info("---------- IP To table2 completed----------------");
    }
    /**
     * 安装table1中的流表项，完全用于统计流量矩阵信息，没有其它作用。
     * 根据源目的IP以及vland信息来统计.
     * @param srcIP
     * @param dstIP
     * @param vlanId
     * @param deviceId
     */
    private void installFlowTable1(IpPrefix srcIP, IpPrefix dstIP, String vlanId, DeviceId deviceId) {
        DefaultFlowRule.Builder ruleBuilder = DefaultFlowRule.builder();
        TrafficSelector.Builder selectBuilder = DefaultTrafficSelector.builder();
        selectBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_TCP)
                .matchIPSrc(srcIP)
                .matchIPDst(dstIP)
                .matchVlanId(VlanId.vlanId(Short.parseShort(vlanId)));
        TrafficTreatment.Builder trafficBuilder = DefaultTrafficTreatment.builder();
        trafficBuilder.transition(2);
        ruleBuilder.withSelector(selectBuilder.build())
                .withPriority(20000)
                .withTreatment(trafficBuilder.build())
                .forTable(1)
                .fromApp(appId)
                .makePermanent()
                .forDevice(deviceId);
        FlowRuleOperations.Builder flowRulebuilder = FlowRuleOperations.builder();
        flowRulebuilder.add(ruleBuilder.build());
        flowRuleService.apply(flowRulebuilder.build());
    }

    /**
     * 通过五元组信息以及分类信息安装流表项到table0.此流表项优先级最高.
     * @param srcPort
     * @param dstPort
     * @param srcIp
     * @param dstIP
     * @param protocal
     * @param vlanId
     * @param deviceId
     */
    private void installBy5Tuple(String srcPort, String dstPort, String srcIp, String dstIP, String protocal,
                                String vlanId, DeviceId deviceId) {
        DefaultFlowRule.Builder ruleBuilder = DefaultFlowRule.builder();
        TrafficSelector.Builder selectBuilder = DefaultTrafficSelector.builder();
        selectBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchVlanId(VlanId.ANY)
                .matchIPSrc(IpPrefix.valueOf(srcIp))
                .matchIPDst(IpPrefix.valueOf(dstIP));
        if (protocal.equals("UDP")) {
            selectBuilder.matchUdpSrc(TpPort.tpPort(Integer.parseInt(srcPort)))
                    .matchUdpDst(TpPort.tpPort(Integer.parseInt(dstPort)))
                    .matchIPProtocol(IPv4.PROTOCOL_UDP);
        }else {
            selectBuilder.matchTcpSrc(TpPort.tpPort(Integer.parseInt(srcPort)))
                    .matchTcpDst(TpPort.tpPort(Integer.parseInt(dstPort)))
                    .matchIPProtocol(IPv4.PROTOCOL_TCP);
        }
        TrafficTreatment.Builder trafficBuilder = DefaultTrafficTreatment.builder();
        trafficBuilder.setVlanId(VlanId.vlanId(Short.parseShort(vlanId)))
                .transition(1);
        ruleBuilder.withSelector(selectBuilder.build())
                .withTreatment(trafficBuilder.build())
                .withPriority(55000)
                .forTable(0)
                .fromApp(appId)
                .withIdleTimeout(300)
                .forDevice(deviceId);
        FlowRuleOperations.Builder flowRulebuilder = FlowRuleOperations.builder();
        flowRulebuilder.add(ruleBuilder.build());
        flowRuleService.apply(flowRulebuilder.build());
    }

    /**
     * 通过源目的IP信息，以及Switch间的Port信息安装流表项，安装到table2，优先级在table2中最高.
     * @param srcIP
     * @param dstIP
     * @param port
     * @param vlanid
     * @param deviceId
     */
    private void installFlow2Table2(IpPrefix srcIP, IpPrefix dstIP, PortNumber port,
                                   String vlanid, DeviceId deviceId) {
        DefaultFlowRule.Builder ruleBuilder = DefaultFlowRule.builder();
        TrafficSelector.Builder selectBuilder = DefaultTrafficSelector.builder();
        selectBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(srcIP)
                .matchIPDst(dstIP)
                .matchVlanId(VlanId.vlanId(Short.parseShort(vlanid)));
        TrafficTreatment.Builder trafficBuilder = DefaultTrafficTreatment.builder();
        trafficBuilder.setOutput(port);
        ruleBuilder.withSelector(selectBuilder.build())
                .withPriority(20000)
                .withTreatment(trafficBuilder.build())
                .forTable(2)
                .fromApp(appId)
                .withIdleTimeout(100)
                .forDevice(deviceId);
        FlowRuleOperations.Builder flowRulebuilder = FlowRuleOperations.builder();
        FlowRule build = ruleBuilder.build();
        flowRulebuilder.add(build);
        flowRuleService.apply(flowRulebuilder.build());
        // 存储优化路由
        optiflowRulesList.add(build);
    }

    /**
     * 通过源目的IP信息，以及Switch间的Port信息安装流表项，安装到table2的默认路由信息.
     * @param srcIP
     * @param dstIP
     * @param port
     * @param deviceId
     */
    private void installDefaultFlow2Table0(IpPrefix srcIP, IpPrefix dstIP,
                                          PortNumber port, DeviceId deviceId) {
        DefaultFlowRule.Builder ruleBuilder = DefaultFlowRule.builder();
        TrafficSelector.Builder selectBuilder = DefaultTrafficSelector.builder();
        selectBuilder.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(srcIP)
                .matchIPDst(dstIP);
//                .matchVlanId(VlanId.vlanId(Short.parseShort("3")));
        TrafficTreatment.Builder trafficBuilder = DefaultTrafficTreatment.builder();
        trafficBuilder.setOutput(port);
        ruleBuilder.withSelector(selectBuilder.build())
                .withPriority(50)
                .withTreatment(trafficBuilder.build())
                .forTable(2)
                .fromApp(appId)
                .makePermanent()
                .forDevice(deviceId);
        FlowRuleOperations.Builder flowRulebuilder = FlowRuleOperations.builder();
        FlowRule build = ruleBuilder.build();
        flowRulebuilder.add(build);
        flowRuleService.apply(flowRulebuilder.build());
        // 存储flowrule
        flowRulesList.add(build);
    }
    /**
     * 安装table2的默认流表，没有匹配到的流，会丢弃，优先级在table2中最低.
     * @param deviceId
     */
    private void tcpToDrop(DeviceId deviceId) {
        DefaultFlowRule.Builder ruleBuilder = DefaultFlowRule.builder();
        TrafficSelector.Builder selectBuilder = DefaultTrafficSelector.builder();
        selectBuilder.matchEthType(Ethernet.TYPE_IPV4);
        TrafficTreatment.Builder trafficBuilder = DefaultTrafficTreatment.builder();
//        trafficBuilder.punt();
        // 匹配不到就丢包
        trafficBuilder.drop();
        ruleBuilder.withSelector(selectBuilder.build())
                .withPriority(5)
                .withTreatment(trafficBuilder.build())
                .forTable(2)
                .fromApp(appId)
                .makePermanent()
                .forDevice(deviceId);
        FlowRuleOperations.Builder flowRulebuilder = FlowRuleOperations.builder();
        flowRulebuilder.add(ruleBuilder.build());
        flowRuleService.apply(flowRulebuilder.build());
    }
    /**
     * 一直等待topo发现完全.
     */
     void waitTopoDiscover() {
        int topoId = 0;
        int linksCount = 0;
        log.info("---------------discover topo waiting.....-----------------------");
        while (true) {
            linksCount = topologyService.currentTopology().linkCount();
            try {
                JsonNode jsonNode = new ObjectMapper().readTree(topoIdx.substring(0, topoIdx.length() - 1));
                topoId = jsonNode.get("topo_idx").intValue();

            } catch (Exception e) {
                e.printStackTrace();
            }
            if (topoId % 2 == 0) {
                if (linksCount == 202) {
                    break;
                }
            } else {
                if (linksCount == 212) {
                    break;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        log.info("--------topo discover complete------------");
    }

    /**
     * 获取相邻switch间连接的port.
     * @param srcId
     * @param dstId
     * @return
     */
     PortNumber getPortInfo(DeviceId srcId, DeviceId dstId) {
        String s = srcId.toString() + '-' + dstId.toString();
        if (stringPortNumberHashMap.containsKey(s)) {
            return stringPortNumberHashMap.get(s);
        } else {
            log.error("------Switch aren't adjacent-------");
            return PortNumber.portNumber(0);
        }
    }

    /**
     * 清空默认流表项.
     */
     void emptyFlow() {
        if (flowRulesList.size() != 0) {
            for (FlowRule flowRule : flowRulesList) {
                flowRuleService.removeFlowRules(flowRule);
            }
            // 清空数据
            flowRulesList.clear();
        }
//        beforeflowRulesList.addAll(flowRulesList);
        log.info("---------have emptyed the default flowEntries----------------");
    }

    /**
     * 清空优化流表项.
     */
     void emptyOptiFlow() {
        if (optiflowRulesList.size() != 0) {
            for (FlowRule flowRule : optiflowRulesList) {
                flowRuleService.removeFlowRules(flowRule);
            }
            // 清空数据
            optiflowRulesList.clear();
        }
//        beforeoptiflowRulesList.addAll(optiflowRulesList);
        log.info("---------have emptyed the optimize flowEntries----------------");
    }

    /**
     * 通过List安装流表项,调用了installFlow2Table2()方法.
     * @param routingList
     * @param vlanid
     */
    public void installFlow2Table2ByList(List<String> routingList,  String vlanid) {
        int size = routingList.size() - 1;
        int src = 0;
        int dst = 0;
        String srcSW = routingList.get(0);
        String dstSW = routingList.get(size);
        IpPrefix srcIp = testHostIpMap.get(srcSW);
        IpPrefix dstIP = testHostIpMap.get(dstSW);
//        log.info("***************path:" + routingList.toString());
        for (int i = 0; i < size; i++) {
            src = i;
            dst = src + 1;
            PortNumber port = getPortInfo(testSwMap.get(routingList.get(src)),
                    testSwMap.get(routingList.get(dst)));
            DeviceId deviceId = testSwMap.get(routingList.get(src));
            installFlow2Table2(srcIp, dstIP, port, vlanid, deviceId);
        }

    }

    /**
     * 通过List安装流表项,installDefaultFlow2Table0()方法,installDefaultFlowMacTable0().
     * @param routingList
     */
    public void installDefaultFlow2Table0ByList(List<String> routingList) {
        int size = routingList.size() - 1;
        int src = 0;
        int dst = 0;
        String srcSW = routingList.get(0);
        String dstSW = routingList.get(size);
        IpPrefix srcIp = testHostIpMap.get(srcSW);
        IpPrefix dstIP = testHostIpMap.get(dstSW);
//        MacAddress srcMac = testHostMacMap.get(srcSW);
//        MacAddress dstMac = testHostMacMap.get(dstSW);
//        log.info("***************path:" + routingList.toString());
        for (int i = 0; i < size; i++) {
            src = i;
            dst = src + 1;
            PortNumber port = getPortInfo(testSwMap.get(routingList.get(src)),
                    testSwMap.get(routingList.get(dst)));
            DeviceId deviceId = testSwMap.get(routingList.get(src));
            installDefaultFlow2Table0(srcIp, dstIP, port, deviceId);
//            installDefaultFlowMacTable0(srcMac, dstMac, port, deviceId);
        }

    }

    /**
     * 每隔1s获取一次流入口速率
     * @return
     */
    public String getFlowRate() {
      /*  Set<String> keySet = testSwMap.keySet();
        JsonObject matrixRes = new JsonObject();
        for(String key : keySet) {
            DeviceId deviceId = testSwMap.get(key);
            PortStatistics deltaStatisticsForPort = deviceService.getDeltaStatisticsForPort(deviceId, PortNumber.portNumber("1"));
            long l = deltaStatisticsForPort.bytesReceived();
            matrixRes.set(key, l);
        }
        return matrixRes.toString();*/
        DeviceId deviceId = testSwMap.get("0");
        PortStatistics deltaStatisticsForPort = deviceService.getDeltaStatisticsForPort(deviceId, PortNumber.portNumber("1"));
        long l = deltaStatisticsForPort.bytesReceived();
        return String.valueOf(l);
    }

    /**
     *  每隔1s获取一次流量矩阵.
     * @return
     */
    public String getMatrix() {
//        log.info("------------------geting Matrix Info----------------------");
        Set<String> keySet = testSwMap.keySet();
        HashMap<String, HashMap<String, Long>> matrixMap1 = new HashMap<>();
        for (String key : keySet) {
            DeviceId deviceId = testSwMap.get(key);
            Iterable<FlowEntry> flowEntries = flowRuleService.getFlowEntries(deviceId);
            Iterator<FlowEntry> iterator = flowEntries.iterator();
            HashMap<String, Long> stringLongHashMap1 = new HashMap<>();
            for (int i = 1; i < switchNum * 3 + 1; i++) {
                stringLongHashMap1.put("" + i, 0L);
            }
            while (iterator.hasNext()) {
                FlowEntry flowEntry = iterator.next();
                TrafficSelector selector = flowEntry.selector();
                Criterion vlanIdcriterion = selector.getCriterion(Criterion.Type.VLAN_VID);
                //有vlanid才进行下一步操作
                if (vlanIdcriterion != null && flowEntry.tableId() == 1) {
                    char cVlanid = vlanIdcriterion.toString().charAt(9);
                    Criterion dstIpcriterion = selector.getCriterion(Criterion.Type.IPV4_DST);
                    String dstIpStr = dstIpcriterion.toString();
//                    log.info(dstIpStr);
                    char c1 = dstIpStr.charAt(16);
                    char c2 = dstIpStr.charAt(17);
                    String s = "";
                    if (c2 == '/') {
                        s = String.valueOf(c1);
                    }else {
                        s = String.valueOf(c1) + c2;
                    }
//                    log.info(s);
                    if (flowEntry.priority() == 20000 && cVlanid == '1') {
                        long bytes = flowEntry.bytes();
                        stringLongHashMap1.put(s, bytes);
                    }
                    if (flowEntry.priority() == 20000 && cVlanid == '2') {
                        long bytes = flowEntry.bytes();
                        String s1 = String.valueOf(Integer.parseInt(s) + switchNum);
                        stringLongHashMap1.put(s1, bytes);
                    }
                    if (flowEntry.priority() == 20000 && cVlanid == '3') {
                        long bytes = flowEntry.bytes();
                        String s2 = String.valueOf(Integer.parseInt(s) + 2 * switchNum);
                        stringLongHashMap1.put(s2, bytes);
                    }
                }
//                iterator.remove();
            }
            matrixMap1.put(key, stringLongHashMap1);
        }

        //遍历matrixMap1，与marixMapStore 获取他们间的差值，再存储为一个map
        HashMap<String, HashMap<String, Long>> subHashMap = new HashMap<>();
        for (int i = 0; i < switchNum; i++) {
            HashMap<String, Long> stringLongHashMap = matrixMap1.get(String.valueOf(i));
            HashMap<String, Long> stringLongHashMapStore = matrixInfoStore.get(String.valueOf(i));
            HashMap<String, Long> newHashMap = new HashMap<>();
            for (int j = 1; j < switchNum * 3 + 1; j++) {
                Long aLong = stringLongHashMap.get(String.valueOf(j));
                Long aLongOld = stringLongHashMapStore.get(String.valueOf(j));
                newHashMap.put(String.valueOf(j), aLong - aLongOld);
            }
            subHashMap.put(String.valueOf(i), newHashMap);
        }
        //更新marixMapstore里的值
        matrixInfoStore.putAll(matrixMap1);

        JsonObject matrixRes = new JsonObject();
        JsonArray jsonArray = new JsonArray();
        Long sum1 = 0L;
        for (int key = 0; key < switchNum; key++) {
            HashMap<String, Long> hashMap = subHashMap.get(String.valueOf(key));
            int f = key + 1;
            for (int i = 1; i < switchNum + 1; i++) {
                if (i != f) {
//                    jsonArray.add(hashMap.get(String.valueOf(i)));
                    sum1 += hashMap.get(String.valueOf(i));
                }
            }
        }
        sum1 = sum1 / 4290;
        Long sum2 = 0L;
        for (int key = 0; key < switchNum; key++) {
            HashMap<String, Long> hashMap = subHashMap.get(String.valueOf(key));
            int f = key + 1;
            for (int i = 1; i < switchNum + 1; i++) {
                if (i != f) {
//                    jsonArray.add(hashMap.get(String.valueOf(i + switchNum)));
                    sum2 += hashMap.get(String.valueOf(i + switchNum));
                }
            }
        }
        sum2 = sum2 / 4290;
        Long sum3 = 0L;
        for (int key = 0; key < switchNum; key++) {
            HashMap<String, Long> hashMap = subHashMap.get(String.valueOf(key));
            int f = key + 1;
            for (int i = 1; i < switchNum + 1; i++) {
                if (i != f) {
//                    jsonArray.add(hashMap.get(String.valueOf(i + 2 * switchNum)));
                    sum3 += hashMap.get(String.valueOf(i + 2 * switchNum));
                }
            }
        }
        sum3 = sum3 / 4290;
//        matrixRes.set("volumes", jsonArray);
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(topoIdx.substring(0, topoIdx.length() - 1));
            JsonNode topoid = jsonNode.get("topo_idx");
            matrixRes.set("topo_idx", topoid.intValue());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        matrixRes.set("average1", sum1);
        matrixRes.set("average2", sum2);
        matrixRes.set("average3", sum3);

//        log.info("----------------------the matrix had been get-------------------------------");
        return matrixRes.toString();
    }

    /**
     *  获取流量矩阵.
     * @return
     */
    public String getTraficMatrix() {
        log.info("------------------geting TraficMatrix----------------------");
        Set<String> keySet = testSwMap.keySet();
        HashMap<String, HashMap<String, Long>> matrixMap1 = new HashMap<>();
        for (String key : keySet) {
            DeviceId deviceId = testSwMap.get(key);
            Iterable<FlowEntry> flowEntries = flowRuleService.getFlowEntries(deviceId);
            Iterator<FlowEntry> iterator = flowEntries.iterator();
            HashMap<String, Long> stringLongHashMap1 = new HashMap<>();
            for (int i = 1; i < switchNum * 3 + 1; i++) {
                stringLongHashMap1.put("" + i, 0L);
            }
            while (iterator.hasNext()) {
                FlowEntry flowEntry = iterator.next();
                TrafficSelector selector = flowEntry.selector();
                Criterion vlanIdcriterion = selector.getCriterion(Criterion.Type.VLAN_VID);
                //有vlanid才进行下一步操作
                if (vlanIdcriterion != null && flowEntry.tableId() == 1) {
                    char cVlanid = vlanIdcriterion.toString().charAt(9);
                    Criterion dstIpcriterion = selector.getCriterion(Criterion.Type.IPV4_DST);
                    String dstIpStr = dstIpcriterion.toString();
//                    log.info(dstIpStr);
                    char c1 = dstIpStr.charAt(16);
                    char c2 = dstIpStr.charAt(17);
                    String s = "";
                    if (c2 == '/') {
                        s = String.valueOf(c1);
                    }else {
                        s = String.valueOf(c1) + c2;
                    }
//                    log.info(s);
                    if (flowEntry.priority() == 20000 && cVlanid == '1') {
                        long bytes = flowEntry.bytes();
                        stringLongHashMap1.put(s, bytes);
                    }
                    if (flowEntry.priority() == 20000 && cVlanid == '2') {
                        long bytes = flowEntry.bytes();
                        String s1 = String.valueOf(Integer.parseInt(s) + switchNum);
                        stringLongHashMap1.put(s1, bytes);
                    }
                    if (flowEntry.priority() == 20000 && cVlanid == '3') {
                        long bytes = flowEntry.bytes();
                        String s2 = String.valueOf(Integer.parseInt(s) + 2 * switchNum);
                        stringLongHashMap1.put(s2, bytes);
                    }
                }
//                iterator.remove();
                }
            matrixMap1.put(key, stringLongHashMap1);
        }

        //遍历matrixMap1，与marixMapStore 获取他们间的差值，再存储为一个map
        HashMap<String, HashMap<String, Long>> subHashMap = new HashMap<>();
        for (int i = 0; i < switchNum; i++) {
            HashMap<String, Long> stringLongHashMap = matrixMap1.get(String.valueOf(i));
            HashMap<String, Long> stringLongHashMapStore = matrixMapStore.get(String.valueOf(i));
            HashMap<String, Long> newHashMap = new HashMap<>();
            for (int j = 1; j < switchNum * 3 + 1; j++) {
                Long aLong = stringLongHashMap.get(String.valueOf(j));
                Long aLongOld = stringLongHashMapStore.get(String.valueOf(j));
                newHashMap.put(String.valueOf(j), aLong - aLongOld);
            }
            subHashMap.put(String.valueOf(i), newHashMap);
        }
        //更新marixMapstore里的值
        matrixMapStore.putAll(matrixMap1);

        JsonObject matrixRes = new JsonObject();
        JsonArray jsonArray = new JsonArray();

        for (int key = 0; key < switchNum; key++) {
            HashMap<String, Long> hashMap = subHashMap.get(String.valueOf(key));
            int f = key + 1;
            for (int i = 1; i < switchNum + 1; i++) {
                if (i != f) {
                    jsonArray.add(hashMap.get(String.valueOf(i)));
                }
            }
        }

        for (int key = 0; key < switchNum; key++) {
            HashMap<String, Long> hashMap = subHashMap.get(String.valueOf(key));
            int f = key + 1;
            for (int i = 1; i < switchNum + 1; i++) {
                if (i != f) {
                    jsonArray.add(hashMap.get(String.valueOf(i + switchNum)));
                }
            }
        }

        for (int key = 0; key < switchNum; key++) {
            HashMap<String, Long> hashMap = subHashMap.get(String.valueOf(key));
            int f = key + 1;
            for (int i = 1; i < switchNum + 1; i++) {
                if (i != f) {
                    jsonArray.add(hashMap.get(String.valueOf(i + 2 * switchNum)));
                }
            }
        }
        matrixRes.set("volumes", jsonArray);
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(topoIdx.substring(0, topoIdx.length() - 1));
            JsonNode topoid = jsonNode.get("topo_idx");
            matrixRes.set("topo_idx", topoid.intValue());
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        log.info("----------------------the matrix had been get-------------------------------");
        return matrixRes.toString() + "*";
    }

    /**
     * 通过ArrayList<List<String>> lists来安装优化的路由信息.
     * @param lists
     */
    public void testInstallRoutingByLists(ArrayList<List<String>> lists, String vlanid) {
        int size = lists.size();
        // 判断如果没有条路由,则报错
        int number = 66 * 65;
        if (size != number) {
            log.error("------the number of routing flow entries is wrong----------");
            log.info(String.valueOf(size));
            return;
        }
        for (int i = 0; i < number; i++) {
            List<String> list = lists.get(i);
            installFlow2Table2ByList(list, vlanid);
        }
    }

    /**
     * 通过ArrayList<List<String>> lists来安装默认的路由信息.
     * @param lists
     */
    public void testInstallDefaultFlowByLists(ArrayList<List<String>> lists) {
        int size = lists.size();
        // 判断如果没有66*65*2条路由,则报错
        int number = 66 * 65;
     /*   if (size != number) {
            log.error("------the number of routing flow entries is wrong----------");
            return;
        }*/
//        log.info("-------intall by list----------");
        for (int i = 0; i < number; i++) {
            List<String> list = lists.get(i);
            installDefaultFlow2Table0ByList(list);
        }
    }

    /**
     * 定时任务,用于上传流量矩阵,并获取优化后的路由.
     * @param interval
     * @param count  为-1时,代表无限循环.
     */
    private void optiRouterMission(int delay, int interval, int count) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                // 获取优化前的链路状况
//                String edgeSpeed = getEdgeSpeed();
                String top3EdgeRate = getTop3EdgeRate();
                String edgeSpeed = getMaxEdgeRate();
                writeToFile(edgeSpeed, "/home/something.txt");
                writeToFile(top3EdgeRate, "/home/top3rate.txt");
                log.info(edgeSpeed);
                //清空优化流表
                emptyOptiFlow();
                log.info("---------upload flow Matrix count " + timesCnt + "----------");
                String  traficMatrix = getTraficMatrix();
                writeToFile(traficMatrix, "/home/traficMatrix.txt");
//                    log.info(traficMatrix);
                executorService.submit(new FlowMarixThread(traficMatrix));
                timesCnt++;
                if (count != -1 && timesCnt > count) {
                    timesCnt = 0;
                    cancel();
                }
            }
        }, delay * 1000, 1000 * interval);
    }

    /**
     * 定时任务，当topo变换后，隔xxs请求默认路由.
     * @param interval
     */
    private void DefaultRouterMission(int interval) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                emptyFlow();
                executorService.submit(new DefaultFlowThread());
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                cancel();
            }
        }, 1000 * interval);
    }

    /**
     * 定时任务，模拟链接断掉后，请求新的路由.
     * @param interval
     */
    private void DisConnectionMission(int interval) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                String top3EdgeRate = getTop3EdgeRate();
                String edgeSpeed = getMaxEdgeRate();
                writeToFile(edgeSpeed, "/home/something.txt");
                writeToFile(top3EdgeRate, "/home/top3rate.txt");
                emptyOptiFlow();
                executorService.submit(new ConnectionDownThread());
                cancel();
            }
        }, 1000 * interval);
    }

    /**
     * 通过string下发流表，主要用于优化路由.
     * @param stringInfo
     */
    private void throughStrInstallFlow(String stringInfo) {
        for (int i = 1; i < 4; i++) {
            try {
                JsonNode jsonNode = new ObjectMapper().readTree(stringInfo);
                JsonNode node = jsonNode.get("res" + i);
                ArrayList<List<String>> arrayLists = new ArrayList<>();
                if (node.isArray()) {
                    for (JsonNode next : node) {
                        String str = next.toString();
                        CharSequence charSequence = str.subSequence(1, str.length() - 1);
                        String[] split = charSequence.toString().split(",");
                        List<String> list1 = Arrays.asList(split);
                        arrayLists.add(list1);
                    }
                }
                String vlanid = String.valueOf(i);
                testInstallRoutingByLists(arrayLists, vlanid);
                log.info("res" + i + "---complete---");
            } catch (Exception e) {
                e.printStackTrace();
                log.info("!!!!!!!!Exception!!!!!!!");
            }
        }

    }

    /**
     * 通过string下发流表，主要用于默认路由.
     * @param stringInfo
     */
    private void throughStrInstallDefaultFlow(String stringInfo) {
        try {
            JsonNode jsonNode = new ObjectMapper().readTree(stringInfo);
            JsonNode node = jsonNode.get("res1");
            ArrayList<List<String>> arrayLists = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode next : node) {
                    String str = next.toString();
                    CharSequence charSequence = str.subSequence(1, str.length() - 1);
                    String[] split = charSequence.toString().split(",");
                    List<String> list1 = Arrays.asList(split);
                    arrayLists.add(list1);
                }
            }
//            log.info("-------parse  arrayLists---------");
            testInstallDefaultFlowByLists(arrayLists);
        } catch (Exception e) {
            e.printStackTrace();
            log.info("!!!!!!!!Exception!!!!!!!");
        }
    }
    /**
     * topo变换后，就发送一个请求默认路由的消息给算法模块,不对接收的信息做处理.
     */
    private void sendDefaultReqMessage() {
        try {
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.connect(new InetSocketAddress("192.168.1.196", 1028));
            ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
            //发送topo信息
            byteBuffer.put(topoIdx.getBytes());
            byteBuffer.flip();
            socketChannel.write(byteBuffer);
            while (byteBuffer.hasRemaining()) {
                socketChannel.write(byteBuffer);
            }
            byteBuffer.clear();
            //接收数据
            int len = 0;
            StringBuilder stringBuilder = new StringBuilder();
            while ((len = socketChannel.read(byteBuffer)) >= 0)  {
                byteBuffer.flip();
                String res = new String(byteBuffer.array(), 0, len);
                byteBuffer.clear();
                stringBuilder.append(res);
            }
            socketChannel.close();

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    /**
     * 定时任务用于每1s存储一次输入到网络中的流
     */
    private void storeFlowRateMission() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                String flowRateStatics = getFlowRate();
                writeToFile(flowRateStatics, "/home/FlowStatics.txt");
            }
        }, 100, 1000);
    }
    /**
     * 定时任务,用于每5s存储一次流量矩阵.
     *
     */
    private void storeMatrixMission() {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                String matrix = getMatrix();
                writeToFile(matrix, "/home/Matrix.txt");
            }
        }, 100, 1000);
    }
    /**
     * 把信息输出到文件.
     * @param content
     */
    public void writeToFile(String content, String filePath) {
        try {
            File file = new File(filePath);
            if(!file.exists()){
                file.createNewFile();
            }
            FileWriter fileWriter = new FileWriter(file.getAbsoluteFile(), true);
            BufferedWriter bw = new BufferedWriter(fileWriter);
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String format = df.format(new Date());
            String out = format + "-->>" + content + "\n";
            bw.write(out);
            bw.close();
//            log.info("finished write to file");
        } catch (IOException e) {
            log.error(e.toString());
        }
    }

    /**
     * 获取最大链路利用率.
     * @return
     */
    private String getMaxEdgeRate() {
        try {
            Topology topology = topologyService.currentTopology();
            TopologyGraph graph = topologyService.getGraph(topology);
            Set<TopologyEdge> edges = graph.getEdges();
            ArrayList<Long> longs = new ArrayList<>();
            for (TopologyEdge edge : edges) {
                ConnectPoint src = edge.link().src();
                long rate = portStatisticsService.load(src).rate();
                longs.add(rate);
            }
            Collections.sort(longs);
            int size = longs.size();
            Long long1 = longs.get(size - 1);
            Double out1 = Double.parseDouble(String.valueOf(long1 * 8 / 10000)) * 0.01;
            return "Max:" + out1.toString() + "***";
        } catch (Exception e) {
            log.info(e.toString());
            return "-------->>>>>>>" + e.toString();
        }
    }

    /**
     * 获取当前topo中链路速率前3的链路.
     * @return
     */
    private String getTop3EdgeRate() {
        try {
            Topology topology = topologyService.currentTopology();
            TopologyGraph graph = topologyService.getGraph(topology);
            Set<TopologyEdge> edges = graph.getEdges();
            ArrayList<Long> longs = new ArrayList<>();
            HashMap<Long, TopologyEdge> hashMap = new HashMap<>();
            for (TopologyEdge edge : edges) {
                ConnectPoint src = edge.link().src();
                long rate = portStatisticsService.load(src).rate();
                longs.add(rate);
                hashMap.put(rate, edge);
            }
            Collections.sort(longs);
            int size = longs.size();
            Long long1 = longs.get(size - 1);
            Long long2 = longs.get(size - 2);
            Long long3 = longs.get(size - 3);
            edgeArrayList.add(hashMap.get(long1));
            edgeArrayList.add(hashMap.get(long2));
            edgeArrayList.add(hashMap.get(long3));
            Double out1 = Double.parseDouble(String.valueOf(long1 * 8 / 10000)) * 0.01;
            Double out2 = Double.parseDouble(String.valueOf(long2 * 8 / 10000)) * 0.01;
            Double out3 = Double.parseDouble(String.valueOf(long3 * 8 / 10000)) * 0.01;
            return "rate1:" + out1.toString() + "  rate2:" +
                    out2.toString() + "  rate3:" + out3.toString() + "***";
        } catch (Exception e) {
            log.info(e.toString());
            return "-------->>>>>>>" + e.toString();
        }
    }

    /**
     * 通过edge获取当前链路所连接的端口速率.
     * @param edge
     * @return
     */
    private String getRateByEdge(TopologyEdge edge){
        try {
            ConnectPoint src = edge.link().src();
            ConnectPoint dst = edge.link().dst();
            long srcRate = portStatisticsService.load(src).rate();
            long dstRate = portStatisticsService.load(dst).rate();
            Double srcOut = Double.parseDouble(String.valueOf(srcRate * 8 / 10000)) * 0.01;
            Double dstOut = Double.parseDouble(String.valueOf(dstRate * 8 / 10000)) * 0.01;

            return "srcRate:" + srcOut.toString() +
                    "   dstRate:" + dstOut.toString() + "*************";
        } catch (Exception e) {
            log.info(e.toString());
            return "-------->>>>>>>" + e.toString();
        }
    }

    /**
     * 获取链路的速率.
     */
    private String getEdgeSpeed() {
        log.info("??????????????????????????");
        try {
            Topology topology = topologyService.currentTopology();
            TopologyGraph graph = topologyService.getGraph(topology);
            Set<TopologyEdge> edges = graph.getEdges();
//        JsonObject jsonObject = new JsonObject();
            ArrayList<Double> doubles = new ArrayList<>();
            for (TopologyEdge edge : edges) {
                ConnectPoint src = edge.link().src();
//                ConnectPoint dst = edge.link().dst();
//                String srcId = reverSwMap.get(src.deviceId());
//                String dstId = reverSwMap.get(dst.deviceId());
                long rate = portStatisticsService.load(src).rate();
//                String key = srcId + "->" + dstId;
//            jsonObject.set(key, rate/1000000);
                Double out = Double.parseDouble(String.valueOf(rate * 8 / 10000)) * 0.01;
                doubles.add(out);
            }
            int size = doubles.size();
            Double sum = 0d;
            for (Double aDouble : doubles) {
                sum += aDouble;
            }
            Double avgSpeed = sum / size;
            Collections.sort(doubles);
            Double minSpeed = doubles.get(0);
            Double maxSpeed = doubles.get(size - 1);

//            List<Double> doubles1 = doubles.subList(doubles.size() - 61, doubles.size() - 1);
//            longs.clear();
//            return doubles1.toString();
            return doubles.toString() + "\n" +
                    "   average:" + avgSpeed +
                    "    min:" + minSpeed +
                    "    max:" + maxSpeed + "*************";
        } catch (Exception e) {
            log.info(e.toString());
            return "-------->>>>>>>" + e.toString();
        }
    }

    /**
     * 定时显示链路速度.
     */
    private void showEdgeSpeed(int interval) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                String edgeSpeed = getEdgeSpeed();
                log.info("==============================================");
                log.info(edgeSpeed);
            }
        }, 1000 * interval, 1000 * interval);
    }

    /**
     * 获取两个优化路由时间段内经过默认路由的数据量.
     */
    private HashMap<String, ArrayList<Long>> getDefaultStatics() {
        Set<String> keySet = testSwMap.keySet();
        HashMap<String, ArrayList<Long>> hashMap = new HashMap<>();
        for (String key : keySet) {
            ArrayList<Long> longArrayList = new ArrayList<>();
            DeviceId deviceId = testSwMap.get(key);
            Iterable<FlowEntry> flowEntries = flowRuleService.getFlowEntries(deviceId);
            Iterator<FlowEntry> iterator = flowEntries.iterator();
            while (iterator.hasNext()) {
                FlowEntry flowEntry = iterator.next();
                if (flowEntry.tableId() == 0 && flowEntry.priority() == 50000) {
                    long bytes = flowEntry.bytes();
                    long packets = flowEntry.packets();
                    longArrayList.add(bytes);
                    longArrayList.add(packets);
                }
            }
            hashMap.put(key, longArrayList);
        }
        return hashMap;
    }

    /**
     * 将新得到的json数据与原json数据作差值，得到数据的增量.
     * @return
     */
    private String subObjStatics(HashMap<String, ArrayList<Long>> map, HashMap<String, ArrayList<Long>> oldMap) {
        Set<String> keySet = map.keySet();
        JsonObject jsonObject = new JsonObject();
        for (String key : keySet) {
            JsonArray jsonArray = new JsonArray();
            ArrayList<Long> longs = map.get(key);
            ArrayList<Long> oldLongs = oldMap.get(key);
            long bytes = longs.get(0) - oldLongs.get(0);
            long packets = longs.get(1) - oldLongs.get(1);
            jsonArray.add(bytes);
            jsonArray.add(packets);
            jsonObject.set(key, jsonArray);
        }
        //更新oldStatics
//        oldStatics.clear();
        oldStatics.putAll(map);
        return jsonObject.toString();
    }

/**
 * 上传流量矩阵线程，并且获取返回的优化路由信息.
 */
public class FlowMarixThread implements Runnable {
    private String matrixMap;
    public FlowMarixThread(String matrixMap) {
        this.matrixMap = matrixMap;
    }
    @Override
    public void run() {
        try {
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.connect(new InetSocketAddress("192.168.1.196", 1028));
//            socketChannel.connect(new InetSocketAddress("172.16.181.1", 1027));
            ByteBuffer byteBuffer = ByteBuffer.allocate(512 * 1024);
            byteBuffer.put(matrixMap.getBytes());
            byteBuffer.flip();
            socketChannel.write(byteBuffer);
            while (byteBuffer.hasRemaining()) {
                socketChannel.write(byteBuffer);
            }
            byteBuffer.clear();
            //接收数据
            int len = 0;
            StringBuilder stringBuilder = new StringBuilder();
            while ((len = socketChannel.read(byteBuffer)) >= 0) {
                byteBuffer.flip();
                String res = new String(byteBuffer.array(), 0, len);
                byteBuffer.clear();
                stringBuilder.append(res);
            }
            routingClq.offer(stringBuilder.toString());
            socketChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

/**
 * 通过获取优化路由信息下发流表项.
 */
public  class RoutingFlowThread implements Runnable {
    @Override
    public void run() {
        // 线程会一直运行,除非被中断掉.
        while (!Thread.currentThread().isInterrupted()) {
        while (!routingClq.isEmpty()) {
            log.info("-----------install routing flow entries---------------");
            String routingInfo = routingClq.poll();
            throughStrInstallFlow(routingInfo);
            log.info("-------------------optimizing routing flow installed---------------------------");
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (edgeArrayList.size() != 0) {
                for (TopologyEdge edge : edgeArrayList) {
                    String rateByEdge = getRateByEdge(edge);
                    writeToFile(rateByEdge, "/home/top3rate.txt");
                }
            }
            writeToFile("\n\n", "/home/top3rate.txt");
            edgeArrayList.clear();
            String maxEdgeRate = getMaxEdgeRate();
            writeToFile(maxEdgeRate, "/home/something.txt");
            writeToFile("\n\n", "/home/something.txt");
//            String edgeSpeed = getEdgeSpeed();
//            writeToFile(edgeSpeed, "/home/something.txt");
//            log.info(edgeSpeed);
           /* HashMap<String, ArrayList<Long>> statics = getDefaultStatics();
            String staticsOut = subObjStatics(statics, oldStatics);
            log.info(staticsOut);*/
        }
    }
  }
}

/**
 * 通过flowClq中的信息安装流表项，此线程会一直存在，只要flowClq非空，就会取出数据，安装流表.
 */
public class InstallFlowByClqThread implements Runnable {
    @Override
    public void run() {
        //线程会一直运行，除非被中断掉
        long cnt = 0L;
        while (!Thread.currentThread().isInterrupted()) {
            while (!flowClq.isEmpty()) {
                String poll = flowClq.poll();
//                    log.info("***" + poll);
                if (poll != null) {
                    String[] split = poll.split("-");
                    String srcPort = split[0];
                    String dstPort = split[1];
                    String srcIP = split[2] + "/32";
                    String dstIP = split[3] + "/32";
                    String protocol = split[4];
                    String v = split[5];
                    String vlanid = String.valueOf(Integer.parseInt(v) + 1);
                    installBy5Tuple(srcPort, dstPort, srcIP, dstIP, protocol, vlanid, testip2swMap.get(srcIP));
                    cnt++;
//                        log.info("&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&");
//                        log.info(String.valueOf(cnt));
                }

            }
        }
    }
}


/**
 * 默认路由的线程.
 */
public class DefaultFlowThread implements Runnable {

    @Override
    public void run() {
        try {
            log.info("--------------default path request--------------");
            SocketChannel socketChannel = SocketChannel.open();
            socketChannel.connect(new InetSocketAddress("192.168.1.196", 1028));
            ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
            //发送topo信息
            byteBuffer.put(topoIdx.getBytes());
            byteBuffer.flip();
            socketChannel.write(byteBuffer);
            while (byteBuffer.hasRemaining()) {
                socketChannel.write(byteBuffer);
            }
            byteBuffer.clear();

            //接收数据
            int len = 0;
            StringBuilder stringBuilder = new StringBuilder();
            while ((len = socketChannel.read(byteBuffer)) >= 0)  {
                byteBuffer.flip();
                String res = new String(byteBuffer.array(), 0, len);
                byteBuffer.clear();
                stringBuilder.append(res);
            }
            socketChannel.close();
            String res = stringBuilder.toString();
            char c = res.charAt(res.length() - 1);
            if (c != '}') {
                log.error("--------recieve failed----------");
                return;
            }
            log.info("------start install default routing----------");
            throughStrInstallDefaultFlow(res);
            log.info("------Congratuation------Default routing has installed--------------");

        } catch (IOException e) {
            e.printStackTrace();
        }

    }
}

    /**
     * 链路断掉处理的线程.
     */
    public class ConnectionDownThread implements Runnable {
        @Override
        public void run() {
            try {
                log.info("--------------connection down and request--------------");
                SocketChannel socketChannel = SocketChannel.open();
                socketChannel.connect(new InetSocketAddress("192.168.1.196", 1028));
                ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
                //发送断掉的链路以及topo信息
                JsonObject sendInfo = new JsonObject();
                JsonArray jsonArray = new JsonArray();
                jsonArray.add(0);
                jsonArray.add(1);
                jsonArray.add(1);
                jsonArray.add(0);
                sendInfo.set("disconnectEdge", jsonArray);
                try {
                    JsonNode jsonNode = new ObjectMapper().readTree(topoIdx.substring(0, topoIdx.length() - 1));
                    JsonNode topoid = jsonNode.get("topo_idx");
                    sendInfo.set("topo_idx", topoid.intValue());
                } catch (JsonProcessingException e) {
                    e.printStackTrace();
                }
                String s = sendInfo.toString() + "*";
                byteBuffer.put(s.getBytes());
                byteBuffer.flip();
                socketChannel.write(byteBuffer);
                while (byteBuffer.hasRemaining()) {
                    socketChannel.write(byteBuffer);
                }
                byteBuffer.clear();

                //接收数据
                int len = 0;
                StringBuilder stringBuilder = new StringBuilder();
                while ((len = socketChannel.read(byteBuffer)) >= 0)  {
                    byteBuffer.flip();
                    String res = new String(byteBuffer.array(), 0, len);
                    byteBuffer.clear();
                    stringBuilder.append(res);
                }
                routingClq.offer(stringBuilder.toString());
                socketChannel.close();

            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }
/**
 * 接收topo的id信息.
 */
public class TopoIdxThread implements Runnable {
    @Override
    public void run() {
        ServerSocketChannel serverSocketChannel = null;
        Selector selector = null;
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        log.info("-------topoidxthread   running----------");
        try {
            serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.bind(new InetSocketAddress("0.0.0.0", 1029));
            serverSocketChannel.configureBlocking(false);
            selector = Selector.open();
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            while (selector.select() > 0) {
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey next = iterator.next();
                    iterator.remove();
                    if (next.isAcceptable()) {
                        SocketChannel accept = serverSocketChannel.accept();
                        accept.configureBlocking(false);
                        accept.register(selector, SelectionKey.OP_READ);
                    } else if (next.isReadable()) {
                        SocketChannel channel = (SocketChannel) next.channel();
                        int len = 0;
                        StringBuilder stringBuilder = new StringBuilder();
                        while ((len = channel.read(buffer)) >= 0) {
                            buffer.flip();
                            String res = new String(buffer.array(), 0, len);
                            buffer.clear();
                            stringBuilder.append(res);
                        }
                        //清空优化路由
//                        emptyOptiFlow();
                        // 接收topo信息
                        topoIdx = stringBuilder.toString() + "*";
                        log.info("=========" + topoIdx + "==========");
                        //发送请求默认路由信息，但不对接收数据进行处理
                        sendDefaultReqMessage();
                        // topo变化35s后请求默认路由
                        DefaultRouterMission(45);
                        // 等待topo发现完全
                        waitTopoDiscover();
                        //重新设置switch间的端口信息
                        setPortInfo();
                     /*   try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }*/
//                        DisConnectionMission(65);
                        //请求优化路由
                        optiRouterMission(15, 60, 0);
                        channel.close();
                    }
                }

            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            // 释放相关的资源
            if (selector != null) {
                try {
                    selector.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (serverSocketChannel != null) {
                try {
                    serverSocketChannel.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
  }
}


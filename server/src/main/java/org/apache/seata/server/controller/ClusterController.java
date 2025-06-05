/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.seata.server.controller;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Resource;

import com.alipay.sofa.jraft.RouteTable;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import org.apache.seata.common.ConfigurationKeys;
import org.apache.seata.common.metadata.MetadataResponse;
import org.apache.seata.common.metadata.Node;
import org.apache.seata.common.result.Result;
import org.apache.seata.common.rpc.http.HttpContext;
import org.apache.seata.common.util.StringUtils;
import org.apache.seata.config.ConfigurationFactory;
import org.apache.seata.server.cluster.manager.ClusterWatcherManager;
import org.apache.seata.server.cluster.raft.RaftServer;
import org.apache.seata.server.cluster.raft.RaftServerManager;
import org.apache.seata.server.cluster.raft.sync.msg.dto.RaftClusterMetadata;
import org.apache.seata.server.cluster.watch.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.apache.seata.common.ConfigurationKeys.STORE_MODE;
import static org.apache.seata.common.DefaultValues.DEFAULT_SEATA_GROUP;

@RestController
@RequestMapping("/metadata/v1")
public class ClusterController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterController.class);

    @Resource
    private ClusterWatcherManager clusterWatcherManager;

    @PostMapping("/changeCluster")
    public Result<?> changeCluster(@RequestParam String raftClusterStr) {
        Result<?> result = new Result<>();
        final Configuration newConf = new Configuration();
        if (!newConf.parse(raftClusterStr)) {
            result.setMessage("fail to parse initConf:" + raftClusterStr);
        } else {
            RaftServerManager.groups().forEach(group -> {
                RaftServerManager.getCliServiceInstance().changePeers(group,
                    RouteTable.getInstance().getConfiguration(group), newConf);
                RouteTable.getInstance().updateConfiguration(group, newConf);
            });
        }
        return result;
    }

    @GetMapping("/cluster")
    public MetadataResponse cluster(String group) {
        MetadataResponse metadataResponse = new MetadataResponse();
        if (StringUtils.isBlank(group)) {
            group =
                ConfigurationFactory.getInstance().getConfig(ConfigurationKeys.SERVER_RAFT_GROUP, DEFAULT_SEATA_GROUP);
        }
        RaftServer raftServer = RaftServerManager.getRaftServer(group);
        if (raftServer != null) {
            String mode = ConfigurationFactory.getInstance().getConfig(STORE_MODE);
            metadataResponse.setStoreMode(mode);
            RouteTable routeTable = RouteTable.getInstance();
            try {
                routeTable.refreshLeader(RaftServerManager.getCliClientServiceInstance(), group, 1000);
                PeerId leader = routeTable.selectLeader(group);
                if (leader != null) {
                    Set<Node> nodes = new HashSet<>();
                    RaftClusterMetadata raftClusterMetadata = raftServer.getRaftStateMachine().getRaftLeaderMetadata();
                    Node leaderNode = raftServer.getRaftStateMachine().getRaftLeaderMetadata().getLeader();
                    leaderNode.setGroup(group);
                    nodes.add(leaderNode);
                    nodes.addAll(raftClusterMetadata.getLearner());
                    nodes.addAll(raftClusterMetadata.getFollowers());
                    metadataResponse.setTerm(raftClusterMetadata.getTerm());
                    metadataResponse.setNodes(new ArrayList<>(nodes));
                }
            } catch (Exception e) {
                LOGGER.error("there is an exception to getting the leader address: {}", e.getMessage(), e);
            }
        }
        return metadataResponse;
    }

    @PostMapping("/watch")
    public void watch(HttpContext context, @RequestBody Map<String, Object> groupTerms,
        @RequestParam(defaultValue = "28000") Integer timeout) {
        context.setAsync(true);
        if (timeout == null) {
            timeout = 28000;
        }
        Integer finalTimeout = timeout;
        groupTerms.forEach((group, term) -> {
            Watcher<HttpContext> watcher =
                new Watcher<>(group, context, finalTimeout, Long.parseLong(String.valueOf(term)));
            clusterWatcherManager.registryWatcher(watcher);
        });
    }

}

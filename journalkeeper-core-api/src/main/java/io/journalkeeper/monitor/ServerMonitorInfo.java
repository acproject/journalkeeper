package io.journalkeeper.monitor;

import io.journalkeeper.core.api.RaftServer;
import io.journalkeeper.utils.state.StateServer;

import java.net.URI;

/**
 * @author LiYue
 * Date: 2019/11/19
 */
public class ServerMonitorInfo {
    // 时间
    private long timestamp = System.currentTimeMillis();
    // 节点URI
    private URI uri = null;
    // 节点状态	枚举:
    //CREATED, STARTING, RUNNING, STOPPING, STOPPED, START_FAILED, STOP_FAILED
    private StateServer.ServerState state = null;
    // 角色	枚举:
    //VOTER, OBSERVER
    private RaftServer.Roll roll = null;
    // LEADER	当前节点中保存的LEADER URI
    private URI leader = null;
    // 集群配置
    private NodeMonitorInfo nodes = null;
    // 日志信息
    private JournalMonitorInfo journal = null;
    // Voter 信息
    private VoterMonitorInfo voter = null;

    public URI getUri() {
        return uri;
    }

    public void setUri(URI uri) {
        this.uri = uri;
    }

    public StateServer.ServerState getState() {
        return state;
    }

    public void setState(StateServer.ServerState state) {
        this.state = state;
    }

    public RaftServer.Roll getRoll() {
        return roll;
    }

    public void setRoll(RaftServer.Roll roll) {
        this.roll = roll;
    }

    public URI getLeader() {
        return leader;
    }

    public void setLeader(URI leader) {
        this.leader = leader;
    }

    public NodeMonitorInfo getNodes() {
        return nodes;
    }

    public void setNodes(NodeMonitorInfo nodes) {
        this.nodes = nodes;
    }

    public JournalMonitorInfo getJournal() {
        return journal;
    }

    public void setJournal(JournalMonitorInfo journal) {
        this.journal = journal;
    }

    public VoterMonitorInfo getVoter() {
        return voter;
    }

    public void setVoter(VoterMonitorInfo voter) {
        this.voter = voter;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "ServerMonitorInfo{" +
                "timestamp=" + timestamp +
                ", uri=" + uri +
                ", state=" + state +
                ", roll=" + roll +
                ", leader=" + leader +
                ", nodes=" + nodes +
                ", journal=" + journal +
                ", voter=" + voter +
                '}';
    }
}

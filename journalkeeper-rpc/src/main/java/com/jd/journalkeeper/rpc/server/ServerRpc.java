package com.jd.journalkeeper.rpc.server;

import com.jd.journalkeeper.base.Queryable;
import com.jd.journalkeeper.base.Replicable;
import com.jd.journalkeeper.rpc.client.ClientServerRpc;

import java.util.concurrent.CompletableFuture;

/**
 * Server 各节点间的RPC
 * @author liyue25
 * Date: 2019-03-14
 */
public interface ServerRpc<E,  S extends Replicable<S> & Queryable<Q, R>, Q, R> extends ClientServerRpc<E, S, Q, R> {
    CompletableFuture<AsyncAppendEntriesResponse> asyncAppendEntries(AsyncAppendEntriesRequest request);
    CompletableFuture<RequestVoteResponse> requestVote(RequestVoteRequest request);
    CompletableFuture<GetServerEntriesResponse<E>> getServerEntries(GetServerEntriesRequest request);
    // TODO: getServerState
}
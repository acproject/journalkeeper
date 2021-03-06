/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.journalkeeper.rpc.client;

import java.net.URI;

/**
 * ClientServerRpc接入点，管理Client和Server的rpc远程连接。
 * @author LiYue
 * Date: 2019-03-14
 */
public interface ClientServerRpcAccessPoint {

    /**
     * 客户端使用
     * 指定URI获取一个ClientServerRpc实例，一般用于访问Leader
     * @param uri 目标地址
     * @return 给定目标地址的rpc实例。
     */
    ClientServerRpc getClintServerRpc(URI uri);

    void stop();
}

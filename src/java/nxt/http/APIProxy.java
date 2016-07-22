/******************************************************************************
 * Copyright © 2013-2016 The Nxt Core Developers.                             *
 *                                                                            *
 * See the AUTHORS.txt, DEVELOPER-AGREEMENT.txt and LICENSE.txt files at      *
 * the top-level directory of this distribution for the individual copyright  *
 * holder information and the developer policies on copyright and licensing.  *
 *                                                                            *
 * Unless otherwise agreed in a custom licensing agreement, no part of the    *
 * Nxt software, including this file, may be copied, modified, propagated,    *
 * or distributed except according to the terms contained in the LICENSE.txt  *
 * file.                                                                      *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package nxt.http;

import nxt.Constants;
import nxt.Nxt;
import nxt.peer.Peer;
import nxt.peer.Peers;
import nxt.util.Logger;
import nxt.util.ThreadPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class APIProxy {
    private static APIProxy instance = new APIProxy();

    public static final boolean enableAPIProxy = Constants.isLightClient ||
            (Nxt.getBooleanProperty("nxt.enableAPIProxy") && API.openAPIPort == 0 && API.openAPISSLPort == 0);
    public static final int blacklistingPeriod = Nxt.getIntProperty("nxt.apiProxyBlacklistingPeriod") / 1000;
    public static final String forcedServerURL = Nxt.getStringProperty("nxt.forceAPIProxyServerURL", "");

    private volatile String forcedPeerHost;
    private volatile List<String> peersHosts;
    private volatile String mainPeerAnnouncedAddress;

    private ConcurrentHashMap<String, Integer> blacklistedPeers = new ConcurrentHashMap<>();

    private static final Runnable peersUpdateThread = () -> {
        int curTime = Nxt.getEpochTime();
        instance.blacklistedPeers.entrySet().removeIf((entry) -> {
            if (entry.getValue() < curTime) {
                Logger.logDebugMessage("Unblacklisting API peer " + entry.getKey());
                return true;
            }
            return false;
        });
        List<String> currentPeersHosts = instance.peersHosts;
        if (currentPeersHosts != null) {
            for (String host:currentPeersHosts) {
                Peer peer = Peers.getPeer(host);
                if (peer != null) {
                    Peers.connectPeer(peer);
                }
            }
        }
    };

    static {
        if (!Constants.isOffline && enableAPIProxy) {
            ThreadPool.scheduleThread("APIProxyPeersUpdate", peersUpdateThread, 60);
        }
    }

    private APIProxy() {

    }

    public static void init() {}

    public static APIProxy getInstance() {
        return instance;
    }

    public Peer getServingPeer(String requestType) {
        if (forcedPeerHost != null) {
            return Peers.getPeer(forcedPeerHost);
        }

        Peer resultPeer = null;
        List<String> currentPeersHosts = this.peersHosts;
        APIEnum requestAPI = APIEnum.fromName(requestType);
        if (currentPeersHosts != null) {
            for (String host:currentPeersHosts) {
                Peer peer = Peers.getPeer(host);
                if (isPeerConnectable(peer) && !peer.getDisabledAPIs().contains(requestAPI)) {
                    resultPeer = peer;
                    break;
                }
            }
        }

        if (resultPeer == null) {
            List<Peer> connectablePeers = Peers.getPeers(p -> isPeerConnectable(p)
                    && !blacklistedPeers.containsKey(p.getHost()));
            if (!connectablePeers.isEmpty()) {
                //The first peer (element 0 of peersHosts) is chosen at random. Next peers are chosen randomly from a
                // subset of connectable peers that have at least one new API enabled, which was disabled for the
                // previously chosen peers. In worst case the size of peersHosts will be the number of APIs
                Peer peer = getRandomAPIPeer(connectablePeers);
                if (peer != null) {
                    currentPeersHosts = new ArrayList<>();

                    EnumSet<APIEnum> disabledAPIs = EnumSet.noneOf(APIEnum.class);
                    currentPeersHosts.add(peer.getHost());
                    mainPeerAnnouncedAddress = peer.getAnnouncedAddress();
                    if (!peer.getDisabledAPIs().contains(requestAPI)) {
                        resultPeer = peer;
                    }
                    while (!disabledAPIs.isEmpty() && !connectablePeers.isEmpty()) {
                        //remove all peers that do not introduce new enabled APIs
                        connectablePeers.removeIf(p -> p.getDisabledAPIs().containsAll(disabledAPIs));
                        peer = getRandomAPIPeer(connectablePeers);
                        if (peer != null) {
                            currentPeersHosts.add(peer.getHost());
                            if (!peer.getDisabledAPIs().contains(requestAPI)) {
                                resultPeer = peer;
                            }
                            disabledAPIs.retainAll(peer.getDisabledAPIs());
                        }
                    }
                    this.peersHosts = Collections.unmodifiableList(currentPeersHosts);
                    Logger.logDebugMessage("Proxy peers hosts selected: " + currentPeersHosts);
                }
            }
        }

        return resultPeer;
    }

    public void setForcedPeer(Peer peer) {
        if (peer != null) {
            forcedPeerHost = peer.getHost();
            mainPeerAnnouncedAddress = peer.getAnnouncedAddress();
        } else {
            forcedPeerHost = null;
        }
    }

    public String getMainPeerAnnouncedAddress() {
        return mainPeerAnnouncedAddress;
    }

    public static boolean isActivated() {
        return Constants.isLightClient || (enableAPIProxy && Nxt.getBlockchainProcessor().isDownloading());
    }

    public void blacklistHost(String host) {
        List<String> currentPeersHosts = this.peersHosts;
        if (currentPeersHosts != null && currentPeersHosts.contains(host)) {
            this.peersHosts = null;
        }
        blacklistedPeers.put(host, Nxt.getEpochTime() + blacklistingPeriod);
    }

    private Peer getRandomAPIPeer(List<Peer> peers) {
        if (peers.isEmpty()) {
            return null;
        }
        int index = ThreadLocalRandom.current().nextInt(peers.size());
        return peers.get(index);
    }

    public static boolean isOpenAPIPeer(Peer peer) {
        return peer.providesService(Peer.Service.API) || peer.providesService(Peer.Service.API_SSL);
        //return peer.providesService(Peer.Service.API);
    }

    public static boolean isPeerConnectable(Peer peer) {
        return peer != null && isOpenAPIPeer(peer) && peer.getState() == Peer.State.CONNECTED
                && !Peers.isOldVersion(peer.getVersion(), Constants.MIN_PROXY_VERSION)
                && !Peers.isNewVersion(peer.getVersion())
                && peer.getBlockchainState() == Peer.BlockchainState.UP_TO_DATE;
    }
}
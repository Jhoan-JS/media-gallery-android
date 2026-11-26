package local.ttlab.isolated;

import local.ttlab.isolated.IConnectorSockets;
import local.ttlab.isolated.ISessionStore;
import local.ttlab.isolated.ITelegramSession;
import local.ttlab.isolated.IWebExchange;

interface ISourceWorker {
    int isolatedUid();
    ITelegramSession openTelegram(long apiId, String apiHash, ISessionStore storage, IConnectorSockets sockets);
    IWebExchange openWeb(String uri, IConnectorSockets sockets);
}

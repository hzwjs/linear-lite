import { loadHostIdentity } from './device.mjs'
import { readHostConfig } from './host-config.mjs'
import { MacKeychainVault } from './keychain.mjs'
import { HostOAuth } from './oauth.mjs'
import { createHostServer, HOST_PORT } from './server.mjs'
import { createCollaborationClient } from './api-client.mjs'
import { BatchDriver } from './batch-driver.mjs'

/** 后台宿主承载身份、连接和服务器批次。 */
export async function startHost({ stateDirectory, port = HOST_PORT, vault = new MacKeychainVault(),
  fetchImpl = fetch, openBrowser, deviceName }) {
  const config = await readHostConfig(stateDirectory)
  const identity = await loadHostIdentity(stateDirectory)
  const auth = new HostOAuth({ issuer: config.origin, identity, vault, fetchImpl, openBrowser, deviceName })
  const server = createHostServer({ identity, allowedOrigin: config.origin, auth, port })
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve) })
  const authorizationReady = auth.start()
  const client = createCollaborationClient({ baseUrl: config.origin, getAccessToken: () => auth.accessToken(), fetchImpl })
  const batches = new BatchDriver({ client, identity, stateDirectory, vault })
  batches.start()
  return { server, identity, auth, batches, authorizationReady,
    async close() {
      await batches.close()
      await auth.close()
      await authorizationReady
      await new Promise(resolve => { server.close(resolve); server.closeAllConnections() })
    } }
}

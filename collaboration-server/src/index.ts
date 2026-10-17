import { readConfig } from './config.js'
import { CollaborationNode } from './server.js'

const service = new CollaborationNode(readConfig())
await service.listen()
process.stdout.write(`Collaboration server listening on ${service.httpServer.webSocketURL}\n`)

const stop = () => {
  void service.destroy().finally(() => process.exit(0))
}
process.once('SIGINT', stop)
process.once('SIGTERM', stop)

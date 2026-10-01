const mineflayer = require('mineflayer')
const readline = require('readline')

const [host, portText, version, username] = process.argv.slice(2)
if (!host || !portText || !version || !/^[A-Za-z0-9_]{3,16}$/.test(username)) {
  process.stderr.write('Usage: node probe_client.js <host> <port> <version> <username>\n')
  process.exit(2)
}

const emit = (event, fields = {}) => process.stdout.write(JSON.stringify({ event, ...fields }) + '\n')
const position = () => {
  const p = bot.entity?.position
  return p ? { x: p.x, y: p.y, z: p.z } : null
}

const bot = mineflayer.createBot({
  host, port: Number(portText), version, username, auth: 'offline', hideErrors: true
})
let closed = false
bot.on('spawn', () => emit('spawn', { username, position: position() }))
bot.on('messagestr', message => emit('message', { message }))
bot.on('kicked', (reason, loggedIn) => emit('kicked', { reason, loggedIn }))
bot.on('error', error => emit('error', { error: String(error) }))
bot.on('end', reason => { closed = true; emit('end', { reason: String(reason || '') }) })

readline.createInterface({ input: process.stdin }).on('line', line => {
  let request
  try { request = JSON.parse(line) } catch (error) { emit('request_error', { error: String(error) }); return }
  if (request.action === 'chat') {
    try { bot.chat(String(request.message || 'ByteBans private probe')); emit('chat_sent') }
    catch (error) { emit('request_error', { error: String(error) }) }
  } else if (request.action === 'move') {
    const before = position()
    bot.setControlState('forward', true)
    setTimeout(() => {
      bot.setControlState('forward', false)
      emit('moved', { before, after: position() })
    }, Math.max(100, Math.min(5000, Number(request.duration_ms) || 2000)))
  } else if (request.action === 'position') {
    emit('position', { position: position() })
  } else if (request.action === 'quit') {
    bot.quit()
    setTimeout(() => process.exit(0), 500)
  } else {
    emit('request_error', { error: 'Unknown action' })
  }
})

setTimeout(() => { if (!closed) { emit('timeout'); bot.quit(); process.exit(1) } }, 120000)

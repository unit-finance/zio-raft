const zmq = require('zeromq');

async function test() {
  console.log('Creating DEALER with handshakeInterval: 0...');
  const socket = new zmq.Dealer({
    linger: 0,
    sendHighWaterMark: 200000,
    receiveHighWaterMark: 200000,
    immediate: true,
    handshakeInterval: 0, // CRITICAL FIX
    heartbeatInterval: 1000,
    heartbeatTimeToLive: 10000,
    heartbeatTimeout: 30000,
  });

  socket.events.on('connect', (event) => {
    console.log('✓ Connected:', event);
  });
  socket.events.on('disconnect', (event) => {
    console.log('✗ Disconnected:', event);
  });

  console.log('Connecting...');
  socket.connect('tcp://127.0.0.1:7001');

  console.log('Waiting 5 seconds...');
  await new Promise(resolve => setTimeout(resolve, 5000));

  console.log('Closing...');
  socket.close();
}

test().catch(console.error);

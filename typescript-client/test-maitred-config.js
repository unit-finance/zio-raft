const zmq = require('zeromq');

async function test() {
  console.log('Creating DEALER with maitred config (no immediate, no probeRouter, no handshakeInterval)...');
  const socket = new zmq.Dealer({
    linger: 0,
    heartbeatInterval: 100,
    heartbeatTimeToLive: 1000,
    heartbeatTimeout: 1000,
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

const zmq = require('zeromq');

async function test() {
  console.log('Creating DEALER socket with full config...');
  const socket = new zmq.Dealer({
    linger: 0,
    sendHighWaterMark: 200000,
    receiveHighWaterMark: 200000,
    immediate: true,
    heartbeatInterval: 1000,
    heartbeatTimeToLive: 10000,
    heartbeatTimeout: 30000,
  });

  // Add event listeners
  socket.events.on('connect', (event) => {
    console.log('✓ Connected:', event);
  });
  socket.events.on('disconnect', (event) => {
    console.log('✗ Disconnected:', event);
  });
  socket.events.on('connect:delay', (event) => {
    console.log('⏱ Connect delay:', event);
  });

  console.log('Connecting to tcp://127.0.0.1:7001...');
  socket.connect('tcp://127.0.0.1:7001');

  console.log('Waiting 5 seconds (NOT sending any messages)...');
  await new Promise(resolve => setTimeout(resolve, 5000));

  console.log('Closing...');
  socket.close();
  console.log('Done');
}

test().catch(console.error);

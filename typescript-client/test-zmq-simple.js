const zmq = require('zeromq');

async function test() {
  console.log('Creating DEALER socket...');
  const socket = new zmq.Dealer({
    linger: 0,
  });

  // Add event listeners
  socket.events.on('connect', (event) => {
    console.log('Connected:', event);
  });
  socket.events.on('disconnect', (event) => {
    console.log('Disconnected:', event);
  });

  console.log('Connecting to tcp://127.0.0.1:7001...');
  socket.connect('tcp://127.0.0.1:7001');

  console.log('Waiting 2 seconds...');
  await new Promise(resolve => setTimeout(resolve, 2000));

  console.log('Sending test message...');
  await socket.send(Buffer.from([0x7a, 0x72, 0x61, 0x66, 0x74, 0x01, 0x07])); // ConnectionClosed message
  
  console.log('Waiting 2 more seconds...');
  await new Promise(resolve => setTimeout(resolve, 2000));

  console.log('Closing...');
  socket.close();
}

test().catch(console.error);

package com.kimjisub.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class BluetoothService(val listener: BTListener) {
	// Member fields
	private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
	private var acceptThread: AcceptThread? = null
	private var connectThread: ConnectThread? = null
	private var connectedThread: ConnectedThread? = null

	enum class State {
		NONE, LISTEN, CONNECTING, CONNECTED, ERROR
	}

	var state = State.NONE
		set(value) {
			log("state = () $field -> $value")
			field = value
			listener.stateChange(field)
		}

	interface BTListener {
		fun stateChange(state: State?)

		fun connected(deviceName: String?)
		fun connectionFailed()
		fun connectionLost()
		fun read(buffer: ByteArray?, bytes: Int)

		fun write(buffer: ByteArray?)
	}

	// Start
	init {
		state = if (btAdapter == null)
			State.ERROR
		else
			State.LISTEN
	}

	@Synchronized
	fun start() {
		log("start")
		if (connectThread != null) {
			connectThread!!.cancel()
			connectThread = null
		}
		if (connectedThread != null) {
			connectedThread!!.cancel()
			connectedThread = null
		}
		if (acceptThread == null) {
			acceptThread = AcceptThread()
			acceptThread!!.start()
		}
		state = State.LISTEN
	}

	@Synchronized
	fun connect(address: String?) {
		try {
			connect(btAdapter!!.getRemoteDevice(address))
		} catch (ignore: Exception) {
		}
	}

	@Synchronized
	fun connect(device: BluetoothDevice) {
		log("connect to: $device")
		// Cancel any thread attempting to make a connection
		if (state == State.CONNECTING) {
			if (connectThread != null) {
				connectThread!!.cancel()
				connectThread = null
			}
		}
		// Cancel any thread currently running a connection
		if (connectedThread != null) {
			connectedThread!!.cancel()
			connectedThread = null
		}
		// Start the thread to connect with the given device
		connectThread = ConnectThread(device)
		connectThread!!.start()
		state = State.CONNECTING
	}

	@Synchronized
	fun connected(socket: BluetoothSocket?, device: BluetoothDevice) {
		log("connected")
		if (connectThread != null) {
			connectThread!!.cancel()
			connectThread = null
		}
		if (connectedThread != null) {
			connectedThread!!.cancel()
			connectedThread = null
		}
		if (acceptThread != null) {
			acceptThread!!.cancel()
			acceptThread = null
		}
		connectedThread = ConnectedThread(socket)
		connectedThread!!.start()
		listener.connected(device.name)
		state = State.CONNECTED
	}

	@Synchronized
	fun stop() {
		log("stop")
		if (connectThread != null) {
			connectThread!!.cancel()
			connectThread = null
		}
		if (connectedThread != null) {
			connectedThread!!.cancel()
			connectedThread = null
		}
		if (acceptThread != null) {
			acceptThread!!.cancel()
			acceptThread = null
		}
		state = State.NONE
	}

	fun write(out: ByteArray?) { // Create temporary object
		var r: ConnectedThread?
		// Synchronize a copy of the ConnectedThread
		synchronized(this) {
			if (state != State.CONNECTED) return
			r = connectedThread
		}
		// Perform the write unsynchronized
		r!!.write(out)
	}

	private fun connectionFailed() {
		state = State.LISTEN
		listener.connectionFailed()
	}

	private fun connectionLost() {
		state = State.LISTEN
		listener.connectionLost()
	}

	val deviceList: Set<BluetoothDevice>
		get() = btAdapter!!.bondedDevices

	private inner class AcceptThread : Thread() {
		private val mmServerSocket: BluetoothServerSocket?

		override fun run() {
			log("BEGIN acceptThread$this")
			name = "AcceptThread"
			var socket: BluetoothSocket? = null
			// Listen to the server socket if we're not connected
			while (state != BluetoothService.State.CONNECTED) {
				socket = try {
					// This is a blocking call and will only return on a
					// successful connection or an exception
					mmServerSocket!!.accept()
				} catch (e: IOException) {
					log("accept() failed")
					break
				}
				// If a connection was accepted
				if (socket != null) {
					synchronized(this@BluetoothService) {
						when (state) {
							BluetoothService.State.LISTEN, BluetoothService.State.CONNECTING ->  // Situation normal. Start the connected thread.
								connected(socket, socket.remoteDevice)
							BluetoothService.State.NONE, BluetoothService.State.CONNECTED ->  // Either not ready or already connected. Terminate new socket.
								try {
									socket.close()
								} catch (e: IOException) {
									log("Could not close unwanted socket")
								}
						}
					}
				}
			}
			log("END acceptThread")
		}

		fun cancel() {
			log("cancel $this")
			try {
				mmServerSocket!!.close()
			} catch (e: IOException) {
				log("close() of server failed")
			}
		}

		init {
			var tmp: BluetoothServerSocket? = null
			// Create a new listening server socket
			try {
				tmp = btAdapter!!.listenUsingRfcommWithServiceRecord(
						NAME,
						MY_UUID
				)
			} catch (e: IOException) {
				log("listen() failed")
			}
			mmServerSocket = tmp
		}
	}

	private inner class ConnectThread(private val mmDevice: BluetoothDevice) : Thread() {
		private val mmSocket: BluetoothSocket?
		override fun run() {
			log("BEGIN connectThread")
			name = "ConnectThread"
			btAdapter!!.cancelDiscovery()
			try { // This is a blocking call and will only return on a
				mmSocket!!.connect()
			} catch (e: IOException) {
				connectionFailed()
				try {
					mmSocket!!.close()
				} catch (e2: IOException) {
					log("unable to close() socket during connection failure")
				}
				this@BluetoothService.start()
				return
			}
			// Reset the ConnectThread because we're done
			synchronized(this@BluetoothService) { connectThread = null }
			// Start the connected thread
			connected(mmSocket, mmDevice)
		}

		fun cancel() {
			try {
				mmSocket!!.close()
			} catch (e: IOException) {
				log("close() of connect socket failed")
			}
		}

		init {
			var tmp: BluetoothSocket? = null
			// Get a BluetoothSocket for a connection with the
// given BluetoothDevice
			try {
				tmp = mmDevice.createRfcommSocketToServiceRecord(MY_UUID)
			} catch (e: IOException) {
				log("create() failed")
			}
			mmSocket = tmp
		}
	}

	private inner class ConnectedThread(socket: BluetoothSocket?) : Thread() {
		private val mmSocket: BluetoothSocket?
		private val mmInStream: InputStream?
		private val mmOutStream: OutputStream?
		override fun run() {
			log("BEGIN connectedThread")
			val buffer = ByteArray(1024)
			var bytes: Int
			// Keep listening to the InputStream while connected
			while (true) {
				try {
					bytes = mmInStream!!.read(buffer)
					listener.read(buffer, bytes)
				} catch (e: IOException) {
					log("disconnected")
					connectionLost()
					break
				}
			}
		}

		fun write(buffer: ByteArray?) {
			try {
				mmOutStream!!.write(buffer)
				// Share the sent message back to the UI Activity
				listener.write(buffer)
				/*mHandler.obtainMessage(BluetoothChat.MESSAGE_WRITE, -1, -1, buffer)
					.sendToTarget();*/
			} catch (e: IOException) {
				log("Exception during write")
			}
		}

		fun cancel() {
			try {
				mmSocket!!.close()
			} catch (e: IOException) {
				log("close() of connect socket failed")
			}
		}

		init {
			log("create ConnectedThread")
			mmSocket = socket
			var tmpIn: InputStream? = null
			var tmpOut: OutputStream? = null
			// Get the BluetoothSocket input and output streams
			try {
				tmpIn = socket!!.inputStream
				tmpOut = socket.outputStream
			} catch (e: IOException) {
				log("temp sockets not created")
			}
			mmInStream = tmpIn
			mmOutStream = tmpOut
		}
	}

	fun log(msg: String?) {
		Log.e("com.kimjisub.bt", msg)
	}

	companion object {
		private const val NAME = "BluetoothChat"
		// Unique UUID for this application
		//private static final UUID MY_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
		private val MY_UUID =
				UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
	}
}
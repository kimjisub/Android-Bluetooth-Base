package com.kimjisub.bluetooth

import android.Manifest
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.SimpleAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import kotlinx.android.synthetic.main.activity_main.*
import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.util.*


class MainActivity : AppCompatActivity() {
	val TAG = "com.kimjisub.log"

	private var btService: BluetoothService? = null

	private val geocoder by lazy { Geocoder(this) }


	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)

		log.movementMethod = ScrollingMovementMethod()



		TedPermission.with(this)
				.setPermissionListener(object : PermissionListener {
					override fun onPermissionGranted() {
						initBt()

						btn.setOnClickListener {
							btService?.write("r".toByteArray())
						}
					}

					override fun onPermissionDenied(deniedPermissions: List<String>) {
						Toast.makeText(this@MainActivity, "권한 획득에 실패하였습니다.", Toast.LENGTH_SHORT).show()
						finish()
					}
				})
				.setDeniedMessage("If you reject permission,you can not use this service\n\nPlease turn on permissions at [Setting] > [Permission]")
				.setPermissions(
						Manifest.permission.BLUETOOTH,
						Manifest.permission.BLUETOOTH_ADMIN
				)
				.check()
	}

	private fun initBt() {
		if (btService == null)
			btService = BluetoothService(object : BluetoothService.BTListener {

				override fun stateChange(state: BluetoothService.State?) {
					runOnUiThread {
						when (state) {
							BluetoothService.State.NONE -> {
								Log.d(TAG, "state NONE")
								menu?.getItem(0)?.icon =
										getDrawable(R.drawable.ic_bluetooth_disabled_black_24dp)
							}
							BluetoothService.State.LISTEN -> {
								Log.d(TAG, "state LISTEN")
								menu?.getItem(0)?.icon =
										getDrawable(R.drawable.ic_bluetooth_black_24dp)
							}
							BluetoothService.State.CONNECTING -> {
								Log.d(TAG, "state CONNECTING")
								menu?.getItem(0)?.icon =
										getDrawable(R.drawable.ic_settings_bluetooth_black_24dp)
							}
							BluetoothService.State.CONNECTED -> {
								Log.d(TAG, "state CONNECTED")
								menu?.getItem(0)?.icon =
										getDrawable(R.drawable.ic_bluetooth_connected_black_24dp)
							}
							BluetoothService.State.ERROR -> {
								Log.d(TAG, "state ERROR")
								menu?.getItem(0)?.icon =
										getDrawable(R.drawable.ic_bluetooth_disabled_black_24dp)
							}
						}
					}
				}

				override fun connected(deviceName: String?) {
					Log.d(TAG, "connected $deviceName")
				}

				override fun connectionFailed() {
					Log.d(TAG, "connectionFailed")
				}

				override fun connectionLost() {
					Log.d(TAG, "connectionLost: ")
				}

				var readStringBuilder = StringBuilder()
				var jsonLevel = 0
				override fun read(buffer: ByteArray?, bytes: Int) {
					val string = String(buffer!!, 0, bytes)
					for (c in string.toCharArray()) {
						readStringBuilder.append(c)
						if (c == '[') jsonLevel++ else if (c == ']') jsonLevel--
						if (jsonLevel < 0) jsonLevel = 0
						if (jsonLevel == 0) {
							readJSON(readStringBuilder.toString())
							readStringBuilder = StringBuilder()
						}
					}
				}

				fun readJSON(string: String) {
					runOnUiThread {
						Log.d(TAG, "read: $string")
						try {
							val jsonArray = JSONArray(string)
                            val date = jsonArray.getString(0)
							val gpslet = jsonArray.getDouble(1)
							val gpslong = jsonArray.getDouble(2)
							val tmp = jsonArray.getDouble(3)
							val AcX = jsonArray.getInt(4)
							val AcY = jsonArray.getInt(5)
							val AcZ = jsonArray.getInt(6)
							val GyX = jsonArray.getInt(7)
							val GyY = jsonArray.getInt(8)
							val GyZ = jsonArray.getInt(9)

							jsonArray.remove(1)
							jsonArray.remove(1)

							log.append("$jsonArray\n")
							locate.text = "$gpslet, $gpslong\n${gpsToName(gpslet, gpslong)}"

							Log.d(TAG, "locate: ${gpsToName(gpslet, gpslong)}")

						} catch (e: JSONException) {
						}
					}
				}

				override fun write(buffer: ByteArray?) {
					Log.d(TAG, "write: " + String(buffer!!))
				}
			})
	}

	private fun btList() {
		when (btService!!.state) {
			BluetoothService.State.LISTEN, BluetoothService.State.CONNECTED -> {
				val builderSingle: AlertDialog.Builder = AlertDialog.Builder(this@MainActivity)
				builderSingle.setTitle("블루투스 장치를 선택하세요")
				val deviceList = ArrayList<HashMap<String, String?>>()
				for (device in btService!!.deviceList) {
					val item: HashMap<String, String?> = HashMap()
					item["name"] = device.name
					item["address"] = device.address
					deviceList.add(item)
				}
				val simpleAdapter = SimpleAdapter(
						this@MainActivity,
						deviceList,
						android.R.layout.simple_list_item_2,
						arrayOf("name", "address"),
						intArrayOf(android.R.id.text1, android.R.id.text2)
				)
				builderSingle.setAdapter(simpleAdapter) { dialog, index ->
					val item =
							simpleAdapter.getItem(index) as HashMap<String, String>
					//String name = item.get("name");
					val address = item["address"]
					btService!!.connect(address)
				}
				builderSingle.show()
			}
			else -> Toast.makeText(this, btService!!.state.name, Toast.LENGTH_SHORT).show()
		}
	}

	fun gpsToName(let: Double, long: Double): String {
		var list: List<Address>? = null
		try {
			list = geocoder.getFromLocation(let, long, 10)
		} catch (e: IOException) {
			e.printStackTrace()
		}
		if (list != null) {
			return if (list.isEmpty())
				"해당되는 주소 정보는 없습니다"
			else
				list[0].getAddressLine(0)
		}
		return "에러가 발생했습니다."
	}

	var menu: Menu? = null
	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		val inflater = menuInflater
		inflater.inflate(R.menu.menu_main, menu)
		this.menu = menu
		return true
	}


	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.menu_bluetooth -> {
				btList()
				return true
			}
		}
		return super.onOptionsItemSelected(item)
	}
}

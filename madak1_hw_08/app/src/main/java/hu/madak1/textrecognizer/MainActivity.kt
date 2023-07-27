package hu.madak1.textrecognizer

// Imports
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.apache.commons.text.similarity.LevenshteinDistance
import java.io.File

// My Pairs
data class ColPair<T, U>(val posX : T, val columns : U) // (Horizontal column pos, columns)
data class RowPair<T, U>(val posY : T, val rows : U) // (Vertical row pos, rows)
// TypeDef
// Structure: Columns[PosX(horizontal), Rows[PosY(vertical), Name(or names)]]
typealias MyArrayListWithPos = ArrayList<ColPair<Int, ArrayList<RowPair<Int, Array<String>>>>>

class MainActivity : AppCompatActivity() {

    // This ArrayList contain the names from the photos
    private var namesAl = ArrayList<ArrayList<Array<String>>>()

    // UI elements
    private lateinit var targetEt: EditText // Enter target name
    private lateinit var resultTv: TextView // Show the names (For debug purpose)
    private lateinit var targetIv: ImageView // Show the image what the user takes
    private lateinit var photoBtn: Button // This button open the camera
    private lateinit var findBtn: Button // This button start the OCR action

    // OCR is performed on this bitmap
    private var targetImg: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init the UI elements
        this.targetEt = findViewById(R.id.main_target_et)
        this.resultTv = findViewById(R.id.main_result_tv)
        this.targetIv = findViewById(R.id.main_image_iv)
        this.photoBtn = findViewById(R.id.main_photo_btn)
        this.findBtn = findViewById(R.id.main_recognize_btn)

        // What the camera button does
        this.photoBtn.setOnClickListener{
            // Check camera permission and ask for if necessary
            //  -> If the user has the permissions, the camera will open
            if (hasCameraPermission()) this.takePicture()
            else this.requestPermissions()
        }

        // What the find button does
        this.findBtn.setOnClickListener {
            // Start the recognition
            this.targetImg = this.targetIv.drawable.toBitmap()
            this.runTextRecognition()
        }

        // What happen if the user tap on the image
        this.targetIv.setOnClickListener {
            // Hide the result and set the image alpha to 100%
            this.resetResult()
        }
    }

    // Set the image alpha to 100% and reset the result text
    private fun resetResult() {
        this.targetIv.alpha = 1.0f // The image alpha will be 100% again
        this.resultTv.setText(R.string.main_result_tv_text) // The result will be reset
    }

    // = [ CAMERA ] ===============================================================================

    // Store the image path
    private lateinit var actImgPath: String

    // If we need the original resolution of the image we have to do this
    private val imgLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val imgBitmap = BitmapFactory.decodeFile(this.actImgPath)
            this.targetIv.setImageBitmap(rotateBitmap(imgBitmap))
        } else Toast.makeText(this, "Failed to take picture", Toast.LENGTH_SHORT).show()
    }

    // Open the camera and save the image (temporarily)
    private fun takePicture() {
        val imgName = "photo"
        val storageDictionary = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val imgFile: File = File.createTempFile(imgName, ".png", storageDictionary)
        this.actImgPath = imgFile.absolutePath
        val imgUri = FileProvider
            .getUriForFile(this, "hu.madak1.textrecognizer.fileprovider", imgFile)
        val imgIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        imgIntent.putExtra(MediaStore.EXTRA_OUTPUT, imgUri)
        this.imgLauncher.launch(imgIntent)
        // Set the image alpha to 100% and reset the result text
        this.resetResult()
    }

    // Rotate the image to the correct state (with a matrix transformation)
    private fun rotateBitmap(source: Bitmap): Bitmap {
        val ei = ExifInterface(this.actImgPath)
        val orientation: Int = ei.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        return Bitmap
            .createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    // = [ Text recognition ] =====================================================================

    // Run OCR
    //  - Load the image
    //  - Load the text recognizer (Set the language to Latin (DEFAULT_OPTIONS))
    //  - Disable the find button (Avoid double tap on the button while OCR is running)
    //  - If something went wrong then print the error to the console else handle the result
    private fun runTextRecognition() {
        val image = InputImage.fromBitmap(this.targetImg!!, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        this.findBtn.isEnabled = false
        recognizer.process(image)
            .addOnSuccessListener { texts ->
                this.findBtn.isEnabled = true
                processTextRecognitionResult(texts)
            }
            .addOnFailureListener { e ->
                this.findBtn.isEnabled = true
                e.printStackTrace()
            }
    }

    // Handle the result
    private fun processTextRecognitionResult(texts: Text) {

        // - Setup --------------------------------------------------------------------------------

        // Store the blocks (Result representation: Blocks[Lines[Element[Symbols]]])
        val blocks: List<Text.TextBlock> = texts.textBlocks
        // If the recogniser didn't find any text then alert the user and do nothing
        if (blocks.isEmpty()) {
            Toast.makeText(this, "No text found", Toast.LENGTH_SHORT).show()
            return
        }

        // Reset the results and the names
        this.resetResult()
        this.namesAl = arrayListOf()

        // - Store blocks in an arrayList -----------------------------------------------------------

        // Store the names in an arrayList which contain information about the positions too
        val arrayList = MyArrayListWithPos()
        // Goes through the blocks
        for (i in blocks.indices) {
            // Store the left upper corner (x and y coordinates) of each boxes
            val blockPosX: Int = blocks[i].cornerPoints?.get(0)?.x!!
            val blockPosY: Int = blocks[i].cornerPoints?.get(0)?.y!!
            // Is the x coordinate of the new block close to any of the previous columns?
            val targetIdx = findNearColIdx(blockPosX, arrayList)
            // If it is a new column (targetIdx == -1) then insert into the arrayList
            // Else add the new block to the proper column
            if (targetIdx == -1) {
                arrayList.add(ColPair(blockPosX, arrayListOf(RowPair(blockPosY, blocks[i].text.split(" ").toTypedArray()))))
            } else arrayList[targetIdx].columns.add(RowPair(blockPosY, blocks[i].text.split(" ").toTypedArray()))
        }

        // Sort the columns by posX and sort the rows by posY
        arrayList.sortBy { it.posX }
        for (al in arrayList) al.columns.sortBy { it.posY }

        // Check the actual array with Logcat
        Log.i("MyTest - Array with pos", arrayList.toString())

        // - Handle the empty cells ---------------------------------------------------------------

        // Find the empty cells (Return a 2D Array)
        val blankIdx = findBlankCellIdx(arrayList)
        // Check the result with Logcat
        Log.i("MyTest - Empty indexes", blankIdx.toString())
        // Goes through the empty cells and add them to the proper columns and the proper rows
        for (i in blankIdx.indices) {
            for (j in blankIdx[i]) {
                arrayList[i].columns.add(j, RowPair(0, arrayOf("Empty")))
            }
        }

        // - Remove pos from the array ------------------------------------------------------------

        // Goes through the columns
        for (column in arrayList) {
            // Create a new empty ArrayList
            val columns = ArrayList<Array<String>>()
            // Goes through the rows
            for (rows in column.columns) {
                // Add the rows (Only the names) to the new ArrayList
                columns.add(rows.rows)
            }
            // Add the new ArrayList (A column) to the namesArrayList
            this.namesAl.add(columns)
        }

        // Check the actual array with Logcat
        Log.i("MyTest - FinalArray", this.namesAl.toString())

        // - Show results -------------------------------------------------------------------------

        val targetIdx = this.findClosestIndex()

        // Go through the array which contains the names
        for (i in this.namesAl.indices) {
            // If new column coming then add an extra line (Separate the columns under each other)
            var tmp = resultTv.text.toString() + "\n"
            this.resultTv.text = tmp
            for (j in namesAl[i].indices) {
                // List the names under each other
                tmp = if (i == targetIdx.first && j == targetIdx.second) {
                    resultTv.text.toString() + "[ " + namesAl[i][j].joinToString(" ") + " ]\n"
                } else {
                    resultTv.text.toString() + namesAl[i][j].joinToString(" ") + "\n"
                }
                this.resultTv.text = tmp
            }
        }

        // The image alpha will be 10% (The result will be more visible)
        this.targetIv.alpha = 0.1f
    }

    // Find the column index by a given posX
    private fun findNearColIdx(
        target: Int,
        arrayList: MyArrayListWithPos
    ): Int {
        // Set how close should the columns be
        val bound = 300
        // The default return is -1 (Not close to any of the columns)
        var tmp = -1
        // Go through the actual columns
        for (i in arrayList.indices) {
            // If the given posX is close to any of the previous columns then select that
            if (arrayList[i].posX-bound <= target && arrayList[i].posX+bound >= target) tmp = i
        }
        // Return the nearest column index (Or -1)
        return tmp
    }

    // Collect the indexes where are the empty cells
    private fun findBlankCellIdx(
        arrayList: MyArrayListWithPos
    ): ArrayList<ArrayList<Int>> {
        // The return arrayList is empty at the start
        val ret = ArrayList<ArrayList<Int>>()
        // Find the largest column (In this column no empty cell)
        // TODO: Another solution for real world usage
        val largestCol = arrayList.maxByOrNull { it.columns.size }!!.columns
        // Go through the columns
        for (al in arrayList) {
            // If the actual column has the same size as the largest then skip
            if (al.columns.size == largestCol.size) ret.add(arrayListOf())
            // Else search for the empty cell in the rows
            else ret.add(findBlankCells(largestCol, al.columns))
        }
        // Return the empty cells index (Structure of the indexes: columns[rows[indexes]])
        return ret
    }

    // Find the empty cell in the rows
    private fun findBlankCells(
        full: ArrayList<RowPair<Int, Array<String>>>,
        act: ArrayList<RowPair<Int, Array<String>>>
    ): ArrayList<Int> {
        // The return arrayList is empty at the start
        val blanks = ArrayList<Int>()
        // Go through the largest column
        for (i in full.indices) {
            // Create a variable which indicate we found an empty cell or not
            var toAdd = true
            for (rowAct in act) {
                // If the largest column's actual row is not close to any of the rows inside the
                // actual column then there is an empty row inside the actual column at that index
                if (full[i].posY-150 < rowAct.posY && full[i].posY+150 > rowAct.posY) toAdd = false
            }
            if (toAdd) blanks.add(i)
        }
        // Return the empty rows
        return blanks
    }

    // Find the closest solution's index
    private fun findClosestIndex(): Pair<Int, Int> {
        // Init Levenshtein Distance
        val levenshtein = LevenshteinDistance()

        var closestIndex = Pair(-1, -1)
        var closestDistance = Int.MAX_VALUE
        // Search for the best solution (closest to the target name)
        for ((cIndex, sublist) in this.namesAl.withIndex()) {
            for ((rIndex, item) in sublist.withIndex()) {
                for (subItem in item) {
                    val distance = levenshtein.apply(subItem, this.targetEt.text)
                    if (distance < closestDistance) {
                        closestDistance = distance
                        closestIndex = Pair(cIndex, rIndex)
                    }
                }
            }
        }
        // Return the closest solution's index
        return closestIndex
    }

    // = [ Permissions ] ==========================================================================

    // Check the camera permission
    private fun hasCameraPermission() =
        ActivityCompat.checkSelfPermission(
            this, android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    // Ask for camera permission
    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (!hasCameraPermission())
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        if (permissionsToRequest.isNotEmpty())
            ActivityCompat.requestPermissions(
                this, permissionsToRequest.toTypedArray(), 111
            )
    }

    // Check the permissions and open the camera if everything is fine
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 111 && grantResults.isNotEmpty()) {
            for (i in grantResults.indices) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Log.i("PermissionReq", "${permissions[i]} is granted!")
                } else Log.i("PermissionReq", "${permissions[i]} is denied!")
            }
        }
        if (this.hasCameraPermission()) this.takePicture()
    }
}
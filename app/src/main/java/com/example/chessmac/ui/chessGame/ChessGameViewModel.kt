package com.example.chessmac.ui.chessGame

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.MoveBackup
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.example.chessmac.ext.toPieceType
import com.example.chessmac.ext.toPiece
import com.example.chessmac.model.ChessBoard
import com.example.chessmac.model.PieceType
import com.example.chessmac.ui.board.ChessBoardListener
import com.example.chessmac.ui.board.PieceOnSquare
import com.example.chessmac.ui.utils.isShortCastleMove
import com.example.chessmac.ui.utils.isLongCastleMove
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class ChessGameViewModel: ViewModel(), ChessBoardListener {
    private var isQuiz: Boolean = false
    private var isLocal: Boolean = false
    private var isStock: Boolean = false

    private val chessBoard = ChessBoard()
    var board = Board()
    private var idMatch: Int = 404
    private var fenString = ""

    private var selectedCell: Square? = null
    private var pieceId = 0
    private var pieceSide: String? = null
    private var promotionMoves: List<Move> = emptyList()
    var currentSideToMove: String? = null
    var bestMove: String = ""

    var quizAttempts = 2
    var quizLeft = 10
    var quizScore = 0.0
    var earnedPoints = 0.0
    var hintCount = 3

    private var hintShown: Boolean = false
    private var lastMove: String? = null
    private var stockDifficulty: String? = null
    var isCheckmate: Boolean = false
    private var stockMove: String = ""

    private val _checkmateEvent = MutableStateFlow(false)
    private val _quizEvent = MutableStateFlow(false)
    private val _stockEvent = MutableStateFlow(false)
    private val _hintEvent = MutableStateFlow(false)
    private val _quizFin = MutableStateFlow(false)
    private val _quizOver = MutableStateFlow(false)
    private val _stockTurn = MutableStateFlow(false)
    val checkmateEvent: StateFlow<Boolean> = _checkmateEvent.asStateFlow()
    val quizEvent: StateFlow<Boolean> = _quizEvent.asStateFlow()
    val stockEvent: StateFlow<Boolean> = _stockEvent.asStateFlow()
    val hintEvent: StateFlow<Boolean> = _hintEvent.asStateFlow()
    val quizFin: StateFlow<Boolean> = _quizFin.asStateFlow()
    val quizOver: StateFlow<Boolean> = _quizOver.asStateFlow()
    val stockTurn: StateFlow<Boolean> = _stockTurn.asStateFlow()

    private val _uiState = MutableStateFlow(
        ChessGameUIState(
            board = chessBoard,
            pieces = emptyList<PieceOnSquare>().toImmutableList(),
            selectedSquare = selectedCell,
            squaresForMove = emptySet<Square>().toImmutableSet(),
            promotions = emptyList<PieceType>().toImmutableList(),
            history = emptyList<String>().toImmutableList(),
        )
    )
    val uiState: StateFlow<ChessGameUIState> = _uiState.asStateFlow()
    private val _gameStarted = MutableStateFlow(false)
    val gameStarted: StateFlow<Boolean> = _gameStarted.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                board.toString()
            }
            emitCurrentUI()
        }
    }

    private fun nextPieceId(): Int = pieceId++

    //Setup localGame environment
    fun startGame() {
        _gameStarted.value = true
        idMatch = startMatchId()
        isLocal = true
    }

    //Setup Quiz environment
    fun startQuiz() {
        _gameStarted.value = true
        _checkmateEvent.value = false
        _quizOver.value = false

        val quizData = getQuiz()
        fenString = quizData.first
        idMatch = quizData.second
        board.loadFromFen(fenString)

        isQuiz = true
        isCheckmate = false
        quizAttempts = 2
        quizLeft--
        emitCurrentUI()
    }

    //Setup stockGame environment
    fun startStockGame(){
        _gameStarted.value = true
        idMatch = startMatchId()
        isStock = true
        showDifficultyDialog()
    }

    //Reset environment
    fun resetGame() {
        _gameStarted.value = false

        board = Board()
        selectedCell = null
        pieceId = 0
        promotionMoves = emptyList()
        isCheckmate = false
        lastMove = null
        currentSideToMove = null
        _checkmateEvent.value = false

        resetStockFish(idMatch)
        emitCurrentUI()
    }

    // Method to handle shake events
    fun handleShake() {
        if (isQuiz){
            if (!hintShown && hintCount > 0) {
                bestMove = bestMoveQuiz(idMatch)
                showHintDialog()
                hintCount--
                emitCurrentUI()
                hintShown = true
            }
        }
        else if (isStock){
            bestMove = bestMoveQuiz(idMatch)
            showHintDialog()
            emitCurrentUI()
            hintShown = true
        }
    }

    //Retrieve matchID from server
    private fun startMatchId() : Int {

        val job = viewModelScope.launch(Dispatchers.IO) { run {
            val name = "https://lralli.pythonanywhere.com" + "/startMatch"
            val url = URL(name)
            val conn = url.openConnection() as HttpsURLConnection
            try {
                conn.run{
                    requestMethod = "GET"
                    val r = JSONObject(InputStreamReader(inputStream).readText())
                    idMatch = r.get("response") as Int
                }
            } catch (e: Exception) {
                Log.e("MATCH ERROR", e.toString())
            }
        }
        }
        runBlocking {
            job.join()
        }
        return idMatch
    }

    //Retrieve fen and ID for quiz
    private fun getQuiz(): Pair<String, Int> {
        var fen = ""
        var idQuiz = 404
        val job = viewModelScope.launch(Dispatchers.IO) { run {
            val name = "https://lralli.pythonanywhere.com" + "/quizStart"
            val url = URL(name)
            val conn = url.openConnection() as HttpsURLConnection
            try {
                conn.run{
                    requestMethod = "GET"
                    val r = JSONObject(InputStreamReader(inputStream).readText())
                    idQuiz = r.get("IDMatch") as Int
                    fen = r.get("response") as String
                }
            } catch (e: Exception) {
                Log.e("MATCH ERROR", e.toString())
            }
        }
        }
        runBlocking {
            job.join()
        }
        return Pair(fen, idQuiz)
    }

    // Set stock game difficulty
    fun setStockDifficulty(difficulty: String){
        stockDifficulty = difficulty
        var setElo = ""
        val job = viewModelScope.launch(Dispatchers.IO) { run {
            val name = "https://lralli.pythonanywhere.com" + "/info" + "?index=" + idMatch.toString() +
                       "&ELO=" + difficulty
            val url = URL(name)
            val conn = url.openConnection() as HttpsURLConnection
            try {
                conn.run{
                    requestMethod = "POST"
                    val r = JSONObject(InputStreamReader(inputStream).readText())
                    setElo = r.get("ELO") as String
                }
            } catch (e: Exception) {
                Log.e("MATCH ERROR", e.toString())
            }
        }
        }
        runBlocking {
            job.join()
        }
    }

    //Reset server's stockfish instance of id
    private fun resetStockFish(id: Int){

        val job = viewModelScope.launch(Dispatchers.IO) {
            run {
                val name = "https://lralli.pythonanywhere.com/reset?index=$id"
                val url = URL(name)
                val conn = url.openConnection() as HttpsURLConnection
                try {
                    conn.run{
                        requestMethod = "GET"
                    }
                } catch (e: Exception) {
                    Log.e("RESET ERROR", e.toString())
                }
            }
        }
        runBlocking {
            job.join()
        }
    }

    //Check if move is mate
    private fun checkMate(
        move: String?,
        prom: String,
        id: Int
    ): Boolean {

        if(move != null){
            val moveString = transformInput(move)
            val idString = id.toString()
            val name = "https://lralli.pythonanywhere.com/" + "?move=" +
                    "" + moveString + prom + "" + "&index=" + idString
            val url = URL(name)
            val conn = url.openConnection() as HttpsURLConnection

            val job = viewModelScope.launch(Dispatchers.IO){ run {
                try {
                    conn.run {
                        requestMethod = "POST"
                        val r = JSONObject(InputStreamReader(inputStream).readText())
                        val mate = r.get("mate") as String
                        isCheckmate = mate.toBoolean()
                    }
                } catch (e: Exception) {
                    Log.e("Move error: ", e.toString())
                }
            }
            }
            runBlocking {
                job.join()
            }
        }
        return isCheckmate
    }

    //♙e2-e4 -> e2e4
    private fun transformInput(input: String?): String? {
        if (input != null) {

            // Castling handling
            if (input.contains("0-0-0")) {
                return "000"
            } else if (input.contains("0-0")) {
                return "00"
            }

            var dashIndex = input.indexOf('-')
            if (input.length > dashIndex + 10) {
                dashIndex = input.indexOf('-', dashIndex + 1)
            }
            val beforeDash = input.substring(dashIndex - 2, dashIndex).lowercase()
            val afterDash = input.substring(dashIndex + 1, dashIndex + 3).lowercase()
            return beforeDash + afterDash
        }
        return null
    }

    //Actions to perform on click of screen (square)
    override fun onSquareClicked(square: Square) {
        if(isStock && board.sideToMove.toString() == "BLACK"){
            return
        }
        if (board.getPiece(square).pieceSide == board.sideToMove) {
            selectedCell = square
        } else {
            doMoveIfCan(square)
        }
        emitCurrentUI()
    }

    //Actions to perform on hold of screen (piece)
    override fun onTakePiece(square: Square) {
        pieceSide = board.getPiece(square).pieceSide.toString()
        if (board.getPiece(square).pieceSide == board.sideToMove) {
            if(isStock && board.sideToMove.toString() == "BLACK"){
                return
            }
            selectedCell = square
            emitCurrentUI()

            if (isQuiz) {
                bestMove = bestMoveQuiz(idMatch)

                if(_quizEvent.value) {
                    _quizEvent.value = false
                }
            }

            if (isStock) {
                if (_stockEvent.value){
                    _stockEvent.value = false
                }
            }
        }
    }

    //Actions to perform on release of screen (piece)
    override fun onReleasePiece(square: Square) {
        viewModelScope.launch {
            if (selectedCell != null && selectedCell == square) {
                // If the last action was just picking up the piece and dropping it back,
                // then don't check for move correctness
                selectedCell = null
                emitCurrentUI()
                return@launch
            }

            if (pieceSide == board.sideToMove.toString()){
                if(isStock && board.sideToMove.toString() == "BLACK"){
                    return@launch
                }

                doMoveIfCan(square)
                emitCurrentUI()

                if(isLocal){
                    if(promotionMoves.isEmpty()) {
                        if (checkMate(lastMove, "", idMatch)) {
                            currentSideToMove = board.sideToMove.toString()
                            showCheckmateDialog()
                        }
                    }
                }

                else if(isQuiz && quizAttempts != 0){
                    if(transformInput(lastMove) != bestMove){
                        lastMove?.let { Log.i("MINE", it) }
                        showQuizDialog()
                        board.loadFromFen(fenString)
                        undoStockfish(idMatch, fenString)
                        quizAttempts -= 1
                        emitCurrentUI()
                        if(quizAttempts == 0){
                            if(quizLeft == 1){
                                storeQuizScore()
                            }
                            _quizOver.value = !_quizOver.value
                        }
                    } else {
                        stockfishMove(lastMove, idMatch)
                        if(isCheckmate){
                            if(quizAttempts == 2){
                                earnedPoints = 1.0
                                quizScore += earnedPoints
                            } else {
                                earnedPoints = 0.5
                                quizScore += earnedPoints
                            }
                            currentSideToMove = board.sideToMove.toString()
                            showCheckmateDialog()
                            _quizOver.value = !_quizOver.value

                            if(quizLeft==0){
                                storeQuizScore()
                            }
                        }
                        else{
                            delay(500)
                            board.doMove(stockMove)
                            hintShown = !hintShown
                            emitCurrentUI()
                        }
                    }
                }

                else if(isStock){
                    _stockTurn.value = !_stockTurn.value
                    stockfishMove(lastMove, idMatch)
                    if(isCheckmate){
                        val winnerSide = if (board.sideToMove.toString() == "WHITE") "W" else "L"
                        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        val difficulty = if (stockDifficulty == "600") "Easy" else if (stockDifficulty == "1300") "Medium" else "Hard"
                        val historyInfo = "$winnerSide/$difficulty/$currentDate"
                        storeGameHistory(historyInfo)

                        currentSideToMove = if (board.sideToMove.toString() == "BLACK") "WHITE" else "BLACK"
                        showCheckmateDialog()
                    }
                    else {
                        if (stockMove!="") {
                            board.doMove(stockMove)
                            emitCurrentUI()
                        }
                    }
                    _stockTurn.value = !_stockTurn.value
                }
            }
        }
    }

    // Store quiz score on firebase db
    private fun storeQuizScore() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val database = FirebaseDatabase.getInstance("https://chessmacc-3aaab-default-rtdb.europe-west1.firebasedatabase.app").getReference("UsersScore")

        uid?.let { userId ->
            // Fetch the nickname from the database
            database.child(uid).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(dataSnapshot: DataSnapshot) {
                    val nickname = dataSnapshot.child("nickname").getValue(String::class.java)
                    if (nickname != null) {
                        // If the nickname exists, proceed to store the quiz score
                        val userScoresRef = database.child(uid).child("scores")
                        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        val newScoreId = userScoresRef.push().key

                        newScoreId?.let { scoreId ->
                            val newScore = mapOf(
                                "score" to quizScore,
                                "date" to currentDate
                            )
                            userScoresRef.child(scoreId).setValue(newScore)
                                .addOnCompleteListener { task ->
                                    if (task.isSuccessful) {
                                        Log.d("Database", "Quiz score stored successfully")
                                    } else {
                                        Log.w("Database", "Error storing quiz score", task.exception)
                                    }
                                }
                        }
                    } else {
                        Log.w("Database", "Nickname not found")
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                }
            })
        } ?: Log.w("Database", "No user logged in")
    }

    // Store stockfish record on firebase db
    private fun storeGameHistory(historyInfo: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        val database = FirebaseDatabase.getInstance("https://chessmacc-3aaab-default-rtdb.europe-west1.firebasedatabase.app").getReference("UsersHistory")

        uid?.let { userId ->
            val userHistoryRef = database.child(userId)
            val historyId = userHistoryRef.push().key

            historyId?.let { id ->
                userHistoryRef.child(id).setValue(historyInfo)
                    .addOnSuccessListener {
                        Log.d("Database", "Game history stored successfully")
                    }
                    .addOnFailureListener { e ->
                        Log.e("Database", "Error storing game history", e)
                    }
            }
        }
    }

    //Trigger stockfish to perform its best move, then return it
    private suspend fun stockfishMove(move: String?, id: Int) {
        if(move != null){
            val moveString = transformInput(move)
            val idString = id.toString()
            val name = "https://lralli.pythonanywhere.com/stockfish" + "?move=" +
                    "" + moveString + "&index=" + idString
            val url = URL(name)
            val conn = url.openConnection() as HttpsURLConnection

            withContext(Dispatchers.IO) {
                try {
                    conn.run {
                        requestMethod = "POST"
                        val r = JSONObject(InputStreamReader(inputStream).readText())
                        val mate = r.get("mate") as String
                        if(mate == "player" || mate == "stockfish"){
                            isCheckmate = true
                        }
                        else{
                            stockMove = r.get("response") as String
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Move error: ", e.toString())
                }
            }
        }
    }

    //Retrieve best move for quiz
    private fun bestMoveQuiz(id: Int
    ): String {

        val idString = id.toString()
        val name = "https://lralli.pythonanywhere.com/bestMove" + "?index=" +
                    idString
        val url = URL(name)
        val conn = url.openConnection() as HttpsURLConnection

        val job = viewModelScope.launch(Dispatchers.IO){ run {
            try {
                conn.run {
                    requestMethod = "POST"
                    val r = JSONObject(InputStreamReader(inputStream).readText())
                    bestMove = r.get("best") as String
                }
                } catch (e: Exception) {
                    Log.e("Move error: ", e.toString())
                }
            }
            }
            runBlocking {
                job.join()
            }
        return bestMove
    }

    //Reset stockfish if quiz mistake
    private fun undoStockfish(id: Int,
                              fen: String){

        val idString = id.toString()
        val fenEnc = URLEncoder.encode(fen, "UTF-8")
        val name = "https://lralli.pythonanywhere.com/undoStockfish" + "?fen=" +
                "" + fenEnc + "" + "&index=" + idString
        val url = URL(name)
        val conn = url.openConnection() as HttpsURLConnection

        val job = viewModelScope.launch(Dispatchers.IO){ run {
            try {
                conn.run {
                    requestMethod = "POST"
                    val r = JSONObject(InputStreamReader(inputStream).readText())
                    Log.i("Result", r.get("result") as String)
                }
            } catch (e: Exception) {
                Log.e("Move error: ", e.toString())
            }
        }
        }
        runBlocking {
            job.join()
        }
    }

    //Trigger checkmate dialogue box
    fun showCheckmateDialog() {
        _checkmateEvent.value = !_checkmateEvent.value
    }

    //Trigger quiz dialogue box
    fun showQuizDialog() {
        _quizEvent.value = !_quizEvent.value
    }

    //Trigger quiz win dialogue box
    fun showQuizFinDialog(){
        _quizFin.value = !_quizFin.value
    }

    //Trigger stock engine difficulty dialogue box
    fun showDifficultyDialog() {
        _stockEvent.value = !_stockEvent.value
    }

    //Trigger quiz hint dialogue box
    fun showHintDialog() {
        _hintEvent.value = !_hintEvent.value
    }

    // Perform standard move or setup promotion pane
    private fun doMoveIfCan(square: Square) {
        // Check if the selected square and target square are the same
        if (selectedCell == square) {
            // Reset the selected cell without doing any move
            selectedCell = null
            return
        }

        val possibleMoves = board.legalMoves().filter {
            it.from == selectedCell && it.to == square
        }
        if (possibleMoves.size == 1) {
            board.doMove(possibleMoves.first())
        } else if (possibleMoves.size > 1) {
            promotionMoves = possibleMoves
        }
        selectedCell = null
    }

    //Handles promotion actions
    override fun onPromotionPieceTypeSelected(pieceType: PieceType, promotionString: String) {
        val promotionPiece = pieceType.toPiece()
        val move = promotionMoves.find { it.promotion == promotionPiece }
        requireNotNull(move)
        board.doMove(move)
        promotionMoves = emptyList()

        emitCurrentUI()

        if (checkMate(lastMove, promotionString, idMatch)) {
            currentSideToMove = board.sideToMove.toString()
            showCheckmateDialog()
        }
    }

    //Update UI based on game state
    private fun emitCurrentUI() {
        _uiState.update { oldUiState ->
            val pieces = calculatePiecesOnSquares(oldUiState.pieces)

            val squaresForMove = board
                .legalMoves()
                .filter { it.from == selectedCell }
                .map { it.to }
                .toImmutableSet()

            val currentHistory = board
                .backup
                .chunked(2)
                .mapIndexed { index, moves ->
                    "${index + 1}. ${moves[0].toHistoryString()} ${
                        moves.getOrNull(1).toHistoryString()
                    }"
                }

            lastMove = currentHistory.lastOrNull()

            val promotions = promotionMoves
                .mapNotNull { it.promotion.toPieceType() }
                .toImmutableList()

            ChessGameUIState(
                board = chessBoard,
                pieces = pieces.toImmutableList(),
                selectedSquare = selectedCell,
                squaresForMove = squaresForMove,
                promotions = promotions,
                history = currentHistory.toImmutableList()
            )
        }
    }

    //Update board based on game state
    private fun calculatePiecesOnSquares(pieces: List<PieceOnSquare>): List<PieceOnSquare> {
        if (pieces.isEmpty()) {
            return Square.values()
                .mapNotNull { square ->
                    board.getPiece(square)
                        .toPieceType()
                        ?.let { pieceType -> PieceOnSquare(nextPieceId(), pieceType, square) }
                }
        }

        val oldPiecesMap = pieces.associateBy { it.square }.toMutableMap()

        val notAddedPieces = mutableMapOf<Square, PieceType>()

        val promotionFrom = promotionMoves.firstOrNull()?.from ?: Square.NONE
        val promotionTo = promotionMoves.firstOrNull()?.to ?: Square.NONE

        return buildList {
            Square.values()
                .forEach { square ->
                    if (square != Square.NONE && square == promotionFrom) {
                        // DO NOTHING. Piece from this square will be processed in "promotionTo" case
                    } else if (square != Square.NONE && square == promotionTo) {
                        val oldPiece = requireNotNull(oldPiecesMap[promotionFrom])
                        val newPiece = oldPiece.copy(square = promotionTo)
                        add(newPiece)

                        oldPiecesMap.remove(promotionFrom)
                        oldPiecesMap.remove(promotionTo)
                    } else {
                        val pieceType = board.getPiece(square).toPieceType()

                        if (pieceType != null) {
                            val oldPiece = oldPiecesMap[square]
                            if (oldPiece?.pieceType == pieceType) {
                                add(oldPiece)
                                oldPiecesMap.remove(square)
                            } else {
                                notAddedPieces[square] = pieceType
                            }
                        }
                    }
                }
            notAddedPieces.forEach { (square, pieceType) ->
                val id = oldPiecesMap.values
                    .find { it.pieceType == pieceType }
                    ?.id
                    ?: nextPieceId()
                add(PieceOnSquare(id, pieceType, square))
            }
        }
    }
}

private fun MoveBackup?.toHistoryString(): String {
    if (this == null) {
        return ""
    }

    return when {
        this.isLongCastleMove() -> "0-0-0"
        this.isShortCastleMove() -> "0-0"
        else -> "${this.movingPiece.fanSymbol} ${this.move.from}-${this.move.to}"
    }
}
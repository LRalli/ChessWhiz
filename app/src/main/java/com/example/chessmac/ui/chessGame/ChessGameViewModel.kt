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
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
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
import javax.net.ssl.HttpsURLConnection

class ChessGameViewModel: ViewModel(), ChessBoardListener {
    private var isQuiz: Boolean = false
    private var isLocal: Boolean = false
    private var isStock: Boolean = false

    private val chessBoard = ChessBoard()
    private var board = Board()
    private var selectedCell: Square? = null
    private var pieceId = 0
    private var promotionMoves: List<Move> = emptyList()
    var currentSideToMove: String? = null
    private var fenString = ""
    var quizAttempts = 2
    var quizLeft = 10
    var quizScore = 0.0
    var earnedPoints = 0.0

    private var idMatch: Int = 404
    private var lastMove: String? = null
    private var isCheckmate: Boolean = false
    private var bestMove: String = ""
    private var stockMove: String = ""

    private val _checkmateEvent = MutableStateFlow(false)
    private val _quizEvent = MutableStateFlow(false)
    val checkmateEvent: StateFlow<Boolean> = _checkmateEvent.asStateFlow()
    val quizEvent: StateFlow<Boolean> = _quizEvent.asStateFlow()

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

    //Check if move is check
    private fun checkMate(
        move: String?,
        prom: String,
        id: Int
    ): Boolean {

        if(move != null){
            val moveString = transformInput(move)
            Log.i("MoveString", "$moveString")

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

    private fun transformInput(input: String?): String? {
        if(input!= null){
            var dashIndex = input.indexOf('-')
            if(input.length > 11) dashIndex += 8
            val beforeDash = input.substring(dashIndex - 2, dashIndex).lowercase()
            val afterDash = input.substring(dashIndex + 1, dashIndex + 3).lowercase()
            return beforeDash + afterDash
        }
        return null
    }

    //Actions to perform on click of screen (square)
    override fun onSquareClicked(square: Square) {
        if (board.getPiece(square).pieceSide == board.sideToMove) {
            selectedCell = square
        } else {
            doMoveIfCan(square)
        }

        emitCurrentUI()
    }

    //Actions to perform on hold of screen (piece)
    override fun onTakePiece(square: Square) {
        if (board.getPiece(square).pieceSide == board.sideToMove) {
            selectedCell = square

            emitCurrentUI()
        }

        bestMove = bestMoveQuiz(idMatch)
        if(_quizEvent.value) {
            _quizEvent.value = false
        }

    }

    //Actions to perform on release of screen (piece)
    override fun onReleasePiece(square: Square) {
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

        else if(isQuiz){
            if(transformInput(lastMove) != bestMove){
                Log.i("NOT BEST", "${transformInput(lastMove)} not equal to $bestMove")
                showQuizDialog()
                board.loadFromFen(fenString)
                undoStockfish(idMatch, fenString)
                quizAttempts -= 1
                emitCurrentUI()
            } else {
                stockfishMove(lastMove, "", idMatch)

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
                    quizAttempts = 0
                }
                else{
                    board.doMove(stockMove)
                    emitCurrentUI()
                }
            }
        }

        else if(isStock){
            stockfishMove(lastMove, "", idMatch)
            Log.i("STOCKMOVE", stockMove)
            board.doMove(stockMove)
            emitCurrentUI()
        }
    }

    private fun stockfishMove(move: String?,
                              prom: String,
                              id: Int) {

        if(move != null){
            val moveString = transformInput(move)
            Log.i("MoveString", "$moveString")

            val idString = id.toString()
            val name = "https://lralli.pythonanywhere.com/stockfish" + "?move=" +
                    "" + moveString + prom + "" + "&index=" + idString
            Log.i("Name", name)
            val url = URL(name)
            val conn = url.openConnection() as HttpsURLConnection

            val job = viewModelScope.launch(Dispatchers.IO){ run {
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
            runBlocking {
                job.join()
            }
        }
    }

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

    private fun undoStockfish(id: Int,
                              fen: String){

        val idString = id.toString()
        val name = "https://lralli.pythonanywhere.com/undoStockfish" + "?fen=" +
                    "" + fen + "" + "&index=" + idString
        val url = URL(name)
        val conn = url.openConnection() as HttpsURLConnection

        val job = viewModelScope.launch(Dispatchers.IO){ run {
            try {
                conn.run {
                    requestMethod = "POST"
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

    fun showCheckmateDialog() {
        _checkmateEvent.value = true
    }

    fun showQuizDialog() {
        _quizEvent.value = true
    }

    private fun doMoveIfCan(square: Square) {
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
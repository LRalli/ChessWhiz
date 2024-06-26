package com.example.chessmac.ext

import com.github.bhlangonijr.chesslib.Piece
import com.example.chessmac.model.PieceType

//Extension function to map Piece enum (used in chesslib) value to its corresponding PieceType enum value.
fun Piece.toPieceType(): PieceType? {

    return when (this) {
        Piece.WHITE_PAWN -> PieceType.PAWN_LIGHT
        Piece.WHITE_KNIGHT -> PieceType.KNIGHT_LIGHT
        Piece.WHITE_BISHOP -> PieceType.BISHOP_LIGHT
        Piece.WHITE_ROOK -> PieceType.ROOK_LIGHT
        Piece.WHITE_QUEEN -> PieceType.QUEEN_LIGHT
        Piece.WHITE_KING -> PieceType.KING_LIGHT
        Piece.BLACK_PAWN -> PieceType.PAWN_DARK
        Piece.BLACK_KNIGHT -> PieceType.KNIGHT_DARK
        Piece.BLACK_BISHOP -> PieceType.BISHOP_DARK
        Piece.BLACK_ROOK -> PieceType.ROOK_DARK
        Piece.BLACK_QUEEN -> PieceType.QUEEN_DARK
        Piece.BLACK_KING -> PieceType.KING_DARK
        Piece.NONE -> null
    }
}
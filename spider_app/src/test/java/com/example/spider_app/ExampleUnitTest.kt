package com.example.spider_app

import kotlin.math.abs

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class Solution {
    fun totalNQueens(n: Int): Int {
        if(n == 0) return 0
        val path = MutableList(n){0}
        return f1(0,n,path)
    }
    fun f1(row: Int,n: Int,path: MutableList<Int>):Int{
        if (row == n){
            return 1
        }
        var answer = 0
        for (i in 0 until  n){
            if (check(path,row,i)){
                path[row] = i
                answer += f1(row+1,n,path)
            }
        }
        return answer
    }
    fun check(path: MutableList<Int>,row: Int,col: Int): Boolean{
        for (i in 0 until row){
            if (path[i] == col|| abs(col-path[i]) == abs(row-i)){
                return false
            }
        }
        return true
    }
}
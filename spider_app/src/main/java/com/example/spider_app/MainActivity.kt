package com.example.spider_app

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.education.data.remote.EducationHelper
import com.dcelysia.csust_spider.education.data.remote.services.AuthService
import com.dcelysia.csust_spider.education.data.remote.services.ExamArrangeService
import com.dcelysia.csust_spider.mooc.data.remote.repository.MoocRepository
import com.example.csustdataget.CampusCard.CampusCardHelper
import com.example.spider_app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val binding : ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val TAG = "MainActivity"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        val loginButton = findViewById<Button>(R.id.loginButton)
        loginButton.setOnClickListener {
            lifecycleScope
            CoroutineScope(Dispatchers.IO).launch {
                RetrofitUtils.ClearClient("moocClient")
                RetrofitUtils.ClearClient("EducationClient")
                try {
                    val loginResult = MoocRepository.instance.login("202408130230","@Wsl20060606")
                    .filter { it !is Resource.Loading }
                    .first()
                    if(loginResult is Resource.Error){
                        Log.d(TAG,"登陆失败, ${loginResult.msg}")
                    }
                    val ssoResult = AuthService.login("202408130230", "@Wsl20060606")
                    val course = EducationHelper.getCourseScheduleByTerm("","2025-2026-1")
                    Log.d(TAG,"course:${course}")
                    if (ssoResult&&(loginResult is Resource.Success)){
                        val course = EducationHelper.getCourseScheduleByTerm("","2025-2026-1")
                        Log.d(TAG,"course:${course}")
                    } else if(loginResult is Resource.Error){
                        Log.d(TAG,"登陆失败, ${loginResult.msg}")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, e.toString())
                }
                val result = ExamArrangeService.getExamArrange("2025-2026-1","期末考试")
                when(result){
                    is Resource.Success -> {
                        Log.d(TAG,"考试安排:${result.data}")
                    }
                    is Resource.Error -> {
                        Log.d(TAG,"考试安排:${result.msg}")
                    }
                    is Resource.Loading -> {
                        Log.d(TAG,"考试安排:加载中")
                    }
                }


                val rl = EducationHelper.getCourseGrades()
                Log.d(TAG,"grades:${rl}")
            }
        }
        binding.course.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val course = EducationHelper.getCourseScheduleByTerm("","2025-2026-1")
                Log.d(TAG,"course:${course}")
            }
            lifecycleScope.launch {
                val queryElectricity =
                    CampusCardHelper.queryElectricity("金盆岭校区", "西苑1栋", "229")
                withContext(Dispatchers.Main) {
                    binding.course.text = queryElectricity.toString()
                }
                Log.d("queryElectricity", "onCreate: $queryElectricity")
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}
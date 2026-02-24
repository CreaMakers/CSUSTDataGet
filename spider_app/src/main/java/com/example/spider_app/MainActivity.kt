package com.example.spider_app

import android.os.Bundle
import android.util.Log
import android.util.Log.v
import android.util.Log.w
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dcelysia.csust_spider.core.KtorUtils
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.edu.EducationRepository
import com.dcelysia.csust_spider.education.data.remote.EducationHelper
import com.dcelysia.csust_spider.education.data.remote.services.AuthService
import com.dcelysia.csust_spider.education.data.remote.services.ExamArrangeService
import com.dcelysia.csust_spider.login.edu.LoginRepository
import com.dcelysia.csust_spider.login.sso.SsoRepository
import com.dcelysia.csust_spider.mooc.data.remote.repository.MoocRepository
import com.example.spider_app.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val binding: ActivityMainBinding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val TAG = "MainActivity"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(binding.root)
        val loginButton = findViewById<Button>(R.id.sso_loginButton)
        loginButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val response = SsoRepository.instance.login("202408130230", "@Wsl20060606")
                val result = response.filter { it !is Resource.Loading }.first()
                when (result) {
                    is Resource.Success -> {
                        Log.d(TAG, "sso登录成功")
                    }

                    is Resource.Error -> {
                        Log.d(TAG, "sso登录失败:${result.msg}")
                    }

                    else -> {}
                }
            }
        }
        val loginOutButton = findViewById<Button>(R.id.loginOutButton)
        loginOutButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch{
                val response = SsoRepository.instance.logout()
                val result = response.filter { it !is Resource.Loading }.first()
                when (result) {
                    is Resource.Success -> {
                        Log.d(TAG, "退出登录成功")
                    }

                    is Resource.Error -> {
                        Log.d(TAG, "退出登录失败:${result.msg}")
                    }

                    else -> {}
                }
            }

        }
        binding.eduLoginButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val response = LoginRepository.instance.login()
                val result = response.filter { it !is Resource.Loading }.first()
                when (result) {
                    is Resource.Success -> {
                        Log.d(TAG, "教务登录成功")
                    }

                    is Resource.Error -> {
                        Log.d(TAG, "教务登录失败:${result.msg}")
                    }

                    else -> {}
                }
            }
        }
        binding.eduGetScoreButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val response = EducationRepository.instance.getCourseGrades()
                val result = response.filter { it !is Resource.Loading }.first()
                when(result){
                    is Resource.Success -> {
                        Log.d(TAG, "获取成绩成功")
                        val data = result.data
                        Log.d(TAG, "获取到成绩：${data.data}")
                    }
                    is Resource.Error -> {
                        Log.d(TAG, "获取成绩失败:${result.msg}")
                    }
                    else -> {}
                }
            }
        }
        binding.eduGetCoursesButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val response = EducationRepository.instance.getCourseScheduleByTerm("","2024-2025-1")
                val result = response.filter { it !is Resource.Loading }.first()
                when(result){
                    is Resource.Success->{
                        Log.d(TAG, "获取课表成功")
                        val data = result.data
                        Log.d(TAG, "获取到课表：${data}")

                    }
                    is Resource.Error->{
                        Log.d(TAG, "获取课表失败:${result.msg}")
                    }
                    else -> {
                    }
                }
            }

        }
        binding.campusGetElectricityButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {

            }

        }
    }

}

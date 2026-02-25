package com.example.spider_app

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.dcelysia.csust_spider.campus.repository.CampusCardRepository
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.edu.repository.EducationRepository
import com.dcelysia.csust_spider.login.edu.LoginRepository
import com.dcelysia.csust_spider.login.mooc.repository.MoocRepository
import com.dcelysia.csust_spider.login.sso.repositroy.SsoRepository
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
                val response = SsoRepository.instance.login("测试自己填写", "测试自己填写")
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
                val response = CampusCardRepository.instance
                    .getElectricity("金盆岭校区", "西苑11栋", "324")
                val result = response.filter { it !is Resource.Loading }.first()
                when (result) {
                    is Resource.Success -> {
                        Log.d(TAG, "获取电费成功")
                        val data = result.data
                        Log.d(TAG,"获取到电费：$data")
                    }

                    is Resource.Error -> {
                        Log.d(TAG, "获取电费失败:${result.msg}")
                    }

                    else -> {}
                }
            }

        }
        binding.moocLoginButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val response = MoocRepository.instance.loginToMooc()
                val result = response.filter { it !is Resource.Loading }.first()

                when(result){
                    is Resource.Success->{
                        Log.d(TAG, "MOOC登录成功")
                    }
                    is Resource.Error->{
                        Log.d(TAG, "MOOC登录失败:${result.msg}")
                    }
                    else -> {
                        Log.d(TAG, "MOOC登录失败:未知问题，没有接收到Resource类信息")
                    }
                }
            }

        }
        binding.campusGetHomeworksButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val response = MoocRepository.instance.getCourses()
                val result = response.filter { it !is Resource.Loading }.first()

                when(result){
                    is Resource.Success->{
                        Log.d(TAG, "课程：${result.data}")
                    }
                    is Resource.Error->{
                        Log.d(TAG, "MOOC登录失败:${result.msg}")
                    }
                    else -> {
                        Log.d(TAG, "没有接收到Resource类信息")
                    }
                }
            }

        }
    }

}

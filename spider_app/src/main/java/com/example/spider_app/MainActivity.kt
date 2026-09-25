package com.example.spider_app

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.dcelysia.csust_spider.core.Resource
import com.dcelysia.csust_spider.core.RetrofitUtils
import com.dcelysia.csust_spider.education.data.remote.EducationData
import com.dcelysia.csust_spider.education.data.remote.EducationHelper
import com.dcelysia.csust_spider.education.data.remote.services.AuthService
import com.dcelysia.csust_spider.education.data.remote.services.ExamArrangeService
import com.dcelysia.csust_spider.mooc.data.remote.repository.MoocRepository
import com.dcelysia.csust_spider.physicsexperiment.PhysicsLabHelper
import com.dcelysia.csust_spider.physicsexperiment.data.remote.error.PhysicsLabError
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
        binding.loginButton.setOnClickListener {
            val username = binding.usernameInput.text.toString().trim()
            val password = binding.passwordInput.text.toString()
            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(this, R.string.login_input_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                RetrofitUtils.ClearClient("moocClient")
                RetrofitUtils.ClearClient("EducationClient")
                try {
                    val loginResult = MoocRepository.instance.login(username, password)
                    .filter { it !is Resource.Loading }
                    .first()
                    if(loginResult is Resource.Error){
                        Log.d(TAG,"登陆失败, ${loginResult.msg}")
                    }
                    val ssoResult = AuthService.login(username, password)
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
                val result = ExamArrangeService.getExamArrange("2025-2026-2")
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
        binding.physicsLabButton.setOnClickListener {
            val username = binding.usernameInput.text.toString().trim()
            val authPassword = binding.passwordInput.text.toString()
            // 平台密码留空时回退用上面的密码：很多同学的平台密码与统一认证相同，
            // 但不强求 —— 不同的话在下面那个输入框里单独填。
            val platformPassword = binding.physicsPlatformPasswordInput.text.toString()
                .ifBlank { authPassword }

            if (username.isBlank() || authPassword.isBlank()) {
                Toast.makeText(this, R.string.login_input_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            CoroutineScope(Dispatchers.IO).launch {
                // 正式 App 里这两项由"绑定学号"流程写入，demo 里手工塞一下
                EducationData.studentId = username
                EducationData.studentPassword = authPassword
                PhysicsLabHelper.setPlatformPassword(platformPassword)

                try {
                    PhysicsLabHelper.clearSession()   // 从干净状态开始，方便反复测试
                    PhysicsLabHelper.login()
                    val alive = PhysicsLabHelper.isLoggedIn()
                    Log.d(TAG, "物理实验登录成功，会话探针：$alive")
                    withContext(Dispatchers.Main) {
                        binding.tvDl.text = "物理实验：登录成功，网关会话有效=$alive"
                    }
                } catch (e: PhysicsLabError) {
                    Log.e(TAG, "物理实验登录失败：${e::class.simpleName} - ${e.message}")
                    withContext(Dispatchers.Main) {
                        binding.tvDl.text = "物理实验：${e::class.simpleName}\n${e.message}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "物理实验登录异常", e)
                    withContext(Dispatchers.Main) {
                        binding.tvDl.text = "物理实验异常：${e.message}"
                    }
                }
            }
        }

        binding.physicsTasksButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val tasks = PhysicsLabHelper.getMyExperiments()
                    Log.d(TAG, "物理实验课表 ${tasks.size} 条:")
                    tasks.forEach {
                        // 按页面列顺序打印全部 8 个字段，便于和平台页面逐列核对
                        Log.d(
                            TAG,
                            "  [${it.courseId}] ${it.courseName} | 批次${it.batch} | ${it.teacher} | " +
                                "${it.location} | ${it.time} | ${it.hours}课时 | ${it.weekday}"
                        )
                    }
                    val index = PhysicsLabHelper.getIndex()
                    Log.d(TAG, "实验目录 ${index.catalog.size} 项，已选 ${index.selected.size} 项")
                    index.selected.forEach { Log.d(TAG, "  已选 [${it.courseId}] ${it.name} (${it.campus})") }
                    withContext(Dispatchers.Main) {
                        binding.tvDl.text = "物理实验课表 ${tasks.size} 条\n" +
                            "目录 ${index.catalog.size} 项 / 已选 ${index.selected.size} 项"
                    }
                } catch (e: PhysicsLabError) {
                    Log.e(TAG, "物理实验查询失败：${e::class.simpleName} - ${e.message}")
                    withContext(Dispatchers.Main) {
                        binding.tvDl.text = "物理实验查询：${e::class.simpleName}\n${e.message}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "物理实验查询异常", e)
                }
            }
        }

        binding.physicsScoresButton.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val scores = PhysicsLabHelper.getScores()
                    Log.d(TAG, "物理实验成绩 ${scores.size} 条:")
                    scores.forEach {
                        // 按页面列顺序打印全部 7 个字段
                        Log.d(
                            TAG,
                            "  [${it.courseCode}] ${it.courseName} | ${it.projectName} | " +
                                "预习${it.previewScore} 操作${it.operationScore} 报告${it.reportScore} 总${it.totalScore}"
                        )
                    }
                    withContext(Dispatchers.Main) {
                        binding.tvDl.text = "物理实验成绩 ${scores.size} 条"
                    }
                } catch (e: PhysicsLabError) {
                    Log.e(TAG, "物理实验成绩查询失败：${e::class.simpleName} - ${e.message}")
                    withContext(Dispatchers.Main) {
                        binding.tvDl.text = "物理实验成绩：${e::class.simpleName}\n${e.message}"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "物理实验成绩查询异常", e)
                }
            }
        }

        binding.dianliang.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val dianliang = CampusCardHelper.queryElectricity("云塘校区","至诚轩五栋A区","a211")
                Log.d(TAG,"电量：”${dianliang}")
                try {
                    Log.d(TAG,"A区：${CampusCardHelper.buildingFallbackMap["至诚轩5栋A区"]}")
                }catch (e: Exception){
                    Log.d(TAG, CampusCardHelper.buildingFallbackMap.toString())
                }
                withContext(Dispatchers.Main){
                    binding.tvDl.text =  dianliang.toString()
                }
            }
        }

        binding.course.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val courseSchedule = EducationHelper.getCourseScheduleByTerm("","2025-2026-2")
                withContext(Dispatchers.Main){
                    binding.tvDl.text = courseSchedule.toString()
                }

            }
        }
        binding.termDetails.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val details = EducationHelper.getSemesterStartDate("2026-2027-1")
                binding.tvDl.text = details
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}

package com.example.hands_mp_test

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        val cameraBtn = findViewById<Button>(R.id.cameraBtn)

        cameraBtn.setOnClickListener {
            // uruchom aktywność do przechwytywania łapki i pokazywania symbolu
            val intentCamera = Intent(this, ClasifyHandActivity::class.java)
            startActivity( intentCamera)
        }
    }





}
package com.example.webrtc1to1

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.webrtc1to1.databinding.FragmentWaitBinding
import java.io.Serializable

data class User(val liveId: Int, val isTeacher: Boolean): Serializable

class WaitFragment : Fragment() {
    private var _binding: FragmentWaitBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWaitBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnOffer.setOnClickListener {
            val user = User(binding.etRoomNum.toString().toInt(), true)

            changeFragment(user)
        }

        binding.btnAnswer.setOnClickListener {
            val user = User(binding.etRoomNum.toString().toInt(), false)

            changeFragment(user)
        }
    }

    private fun changeFragment(user: User) {
        if (childFragmentManager.findFragmentById(R.id.fl) == null) {
            val fragment = LiveFragment.newInstance(user)

            parentFragmentManager.beginTransaction()
                .replace(R.id.fl, fragment)
                .addToBackStack(null)
                .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
package com.flowstate.focus.di

import android.content.Context
import androidx.room.Room
import com.flowstate.focus.data.db.AppDatabase
import com.flowstate.focus.data.db.SessionDao
import com.flowstate.focus.data.db.TaskDao
import com.flowstate.focus.data.db.ExamDao
import com.flowstate.focus.data.repo.SessionRepository
import com.flowstate.focus.data.repo.SessionRepositoryImpl
import com.flowstate.focus.data.repo.TaskRepository
import com.flowstate.focus.data.repo.TaskRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "flowstate.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun sessionDao(db: AppDatabase): SessionDao = db.sessionDao()

    @Provides
    fun taskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    fun examDao(db: AppDatabase): ExamDao = db.examDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun sessions(impl: SessionRepositoryImpl): SessionRepository

    @Binds
    abstract fun tasks(impl: TaskRepositoryImpl): TaskRepository
}

/*
 * Tencent is pleased to support the open source community by making BK-JOB蓝鲸智云作业平台 available.
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-JOB蓝鲸智云作业平台 is licensed under the MIT License.
 *
 * License for BK-JOB蓝鲸智云作业平台:
 * --------------------------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package com.tencent.bk.job.upgrader;

import com.tencent.bk.job.common.util.CompareUtil;
import com.tencent.bk.job.common.util.StringUtil;
import com.tencent.bk.job.upgrader.anotation.ExecuteTimeEnum;
import com.tencent.bk.job.upgrader.anotation.RequireTaskParam;
import com.tencent.bk.job.upgrader.anotation.UpgradeTask;
import com.tencent.bk.job.upgrader.anotation.UpgradeTaskInputParam;
import com.tencent.bk.job.upgrader.task.IUpgradeTask;
import com.tencent.bk.job.upgrader.task.param.AbstractTaskParam;
import com.tencent.bk.job.upgrader.utils.MessageBeatTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.reflections.Reflections;
import org.reflections.scanners.SubTypesScanner;
import org.reflections.scanners.TypeAnnotationsScanner;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class Upgrader {

    public Upgrader() {
    }

    public static void usage() {
        log.info("===========================================Usage==============================================");
        log.info("Usage: use command `java -Dfile.encoding=utf8 -Djob.log.dir=path/to/log/dir " +
            "-Dconfig.file=/path/to/config/file " +
            "-jar upgrader-[x.x.x.x].jar [fromVersion] " +
            "[toVersion] [executeTime]` to start the upgrader, " +
            "then enter the specific parameters related to the " +
            "specific upgrade tasks according to the command line prompt");
        log.info("/path/to/log/dir is the path to log dir, usually set to ${BK_HOME}/logs/job");
        log.info("/path/to/config/file is the path to config file, which is generated by upgrade script, " +
            "usually can be ${BK_HOME}/etc/job/upgrader/upgrader.properties");
        log.info("fromVersion is the current version of Job, example:3.2.7.3");
        log.info("toVersion is the target version of Job to upgrade, example:3.3.4.0");
        log.info("executeTime is the time point to execute upgrade tasks, " +
            "which value can be:BEFORE_UPDATE_JOB/AFTER_UPDATE_JOB, " +
            "BEFORE_UPDATE_JOB means executing upgrade tasks before upgrading job service jar, " +
            "while AFTER_UPDATE_JOB means executing upgrade tasks after upgrading job service jar");
        log.info("Example: java -Dfile.encoding=utf8 -Djob.log.dir=/data/bkee/logs/job" +
            " -Dconfig.file=/data/bkee/etc/job/upgrader/upgrader.properties " +
            "-jar upgrader-3.3.4.0.jar 3.2.7.3 3.3.4.0 " +
            "BEFORE_UPDATE_JOB");
        log.info("==========================================用法说明=============================================");
        log.info("工具用法: 使用命令`java -Dfile.encoding=utf8 -Djob.log.dir=path/to/log/dir" +
            " -Dconfig.file=/path/to/config/file" +
            " -jar upgrader-[x.x.x.x].jar [fromVersion] " +
            "[toVersion] [executeTime]` 启动工具，再根据命令行提示输入与具体版本升级任务相关的特定参数");
        log.info("/path/to/log/dir用于指定工具日志保存路径，通常为${BK_HOME}/logs/job");
        log.info("/path/to/config/file用于指定工具读取的配置文件，该配置文件由部署脚本自动渲染生成，" +
            "路径为${BK_HOME}/etc/job/upgrader/upgrader.properties");
        log.info("fromVersion为当前作业平台版本，如3.2.7.3");
        log.info("toVersion为目标作业平台版本，如3.3.4.0");
        log.info("executeTime为升级任务执行的时间点，取值为BEFORE_UPDATE_JOB、AFTER_UPDATE_JOB，" +
            "在更新作业平台进程前执行本工具填写BEFORE_UPDATE_JOB，更新进程后执行则填写AFTER_UPDATE_JOB");
        log.info("示例：java -Dfile.encoding=utf8 -Djob.log.dir=/data/bkee/logs/job" +
            " -Dconfig.file=/data/bkee/etc/job/upgrader/upgrader.properties" +
            " -jar upgrader-3.3.4.0.jar 3.2.7.3 3.3.4.0 " +
            "BEFORE_UPDATE_JOB");
    }

    public static boolean isTaskNeedToExecute(
        String fromVersion,
        String toVersion,
        String dataStartVersion,
        String taskTargetVersion
    ) {
        // 需要升级的旧数据已经产生
        // && 当前版本比升级任务的目标版本低
        // && 升级任务的目标版本比要升级到的新版本低
        return CompareUtil.compareVersion(dataStartVersion, fromVersion) <= 0
            && CompareUtil.compareVersion(fromVersion, taskTargetVersion) < 0
            && CompareUtil.compareVersion(taskTargetVersion, toVersion) <= 0;
    }

    private static List<Triple<Class<? extends Object>, String, Integer>> findAndFilterUpgradeTasks(
        String fromVersion,
        String toVersion,
        String executeTime
    ) {
        List<Triple<Class<? extends Object>, String, Integer>> upgradeTaskList = new ArrayList<>();
        // 找出所有UpgradeTask
        Reflections reflections = new Reflections(
            "com.tencent.bk.job.upgrader.task",
            new SubTypesScanner(false),
            new TypeAnnotationsScanner()
        );
        Set<Class<?>> upgradeTaskSet = reflections.getTypesAnnotatedWith(UpgradeTask.class);

        // 明确指定运行的任务
        List<String> targetTaskList = null;
        String targetTasksStr = System.getProperty("target.tasks");
        if (StringUtils.isNotBlank(targetTasksStr)) {
            targetTasksStr = targetTasksStr.trim();
            targetTaskList = StringUtil.strToList(targetTasksStr, String.class, ",");
            log.info("targetTaskList={}", targetTaskList);
        }

        // 筛选
        for (Class<?> clazz : upgradeTaskSet) {
            if (targetTaskList != null && !targetTaskList.isEmpty()) {
                // 只运行指定的任务
                if (!targetTaskList.contains(clazz.getSimpleName())) {
                    continue;
                }
            }
            UpgradeTask anotation = clazz.getAnnotation(UpgradeTask.class);
            String dataStartVersion = anotation.dataStartVersion();
            String targetVersion = anotation.targetVersion();
            ExecuteTimeEnum targetExecuteTime = anotation.targetExecuteTime();
            int priority = anotation.priority();
            log.info("Found upgradeTask:[{}] for version {}, dataStartVersion={}, priority={}", clazz.getName(),
                targetVersion, dataStartVersion, priority);
            if (targetExecuteTime.name().equalsIgnoreCase(executeTime)
                && isTaskNeedToExecute(fromVersion, toVersion, dataStartVersion, targetVersion)
            ) {
                log.info("{}-->{},{},Add task {}({},{})", fromVersion, toVersion, executeTime,
                    clazz.getSimpleName(), targetVersion, targetExecuteTime);
                upgradeTaskList.add(Triple.of(clazz, targetVersion, priority));
            } else {
                log.info("{}-->{},{},Ignore task {}({},{})", fromVersion, toVersion, executeTime,
                    clazz.getSimpleName(), targetVersion, targetExecuteTime);
            }
        }
        return upgradeTaskList;
    }

    private static void checkAndInputTaskParams(
        List<Triple<Class<? extends Object>, String, Integer>> upgradeTaskList,
        Properties properties
    ) {
        Map<String, String> paramMap = new HashMap<>();
        Scanner scanner = new Scanner(System.in);
        for (Triple<Class<?>, String, Integer> entry : upgradeTaskList) {
            Class<? extends Object> clazz = entry.getLeft();
            IUpgradeTask upgradeTask = getUpgradeTaskByClass(clazz, properties);
            RequireTaskParam requireTaskParamAnotation = clazz.getAnnotation(RequireTaskParam.class);
            if (requireTaskParamAnotation != null) {
                UpgradeTaskInputParam[] params = requireTaskParamAnotation.value();
                if (params.length > 0) {
                    log.info("upgradeTask [{}] requires {} param:", upgradeTask.getName(), params.length);
                }
                for (int i = 0; i < params.length; i++) {
                    Class<?> paramClass = params[i].value();
                    try {
                        AbstractTaskParam paramInstance = (AbstractTaskParam) paramClass.newInstance();
                        log.info("Param {}", i + 1);
                        log.info("Name: " + paramInstance.getKey());
                        log.info("Description: " + paramInstance.getDescriptionEn());
                        log.info("描述：" + paramInstance.getDescription());
                        // 从JVM参数中取值
                        String valueBySystemProperty = System.getProperty(paramInstance.getKey());
                        if (!StringUtils.isBlank(valueBySystemProperty)) {
                            log.info("Param {} already set by VM options, skip", paramInstance.getKey());
                            properties.setProperty(paramInstance.getKey(), valueBySystemProperty);
                            continue;
                        }
                        // 从配置文件中取值
                        if (properties.containsKey(paramInstance.getKey())) {
                            log.info("Param {} already set in config file, skip", paramInstance.getKey());
                            continue;
                        }
                        // 从已指定参数中取值
                        if (paramMap.containsKey(paramInstance.getKey())) {
                            log.info("Param {} already set by another previous task, skip", paramInstance.getKey());
                            continue;
                        }
                        // 都没有再要求输入
                        log.info("Please input param value and click enter to continue:");
                        log.info("请输入上述提示所描述的参数，按回车键确认输入：");
                        AbstractTaskParam.ParamCheckResult paramCheckResult;
                        String paramValue;
                        do {
                            paramValue = scanner.nextLine();
                            paramCheckResult = paramInstance.checkParam(paramValue);
                            if (!paramCheckResult.isPass()) {
                                log.warn("Error messages:{}", paramCheckResult.getMessageEn());
                                log.warn("错误信息：{}", paramCheckResult.getMessage());
                                log.warn("Param value [{}] is invalid, please input again:", paramValue);
                            }
                        } while (!paramCheckResult.isPass());
                        paramMap.put(paramInstance.getKey(), paramValue.trim());
                    } catch (InstantiationException | IllegalAccessException e) {
                        log.error("Fail to set params for {}", clazz.getSimpleName(), e);
                        break;
                    }
                }
            }
        }
        paramMap.forEach(properties::setProperty);
    }

    public static void main(String[] args) {
        log.info("Upgrader begin to run");
        if (args.length < 3) {
            usage();
            return;
        }
        String fromVersion = args[0].trim();
        String toVersion = args[1].trim();
        String executeTime = args[2].trim();
        log.info("fromVersion={}", fromVersion);
        log.info("toVersion={}", toVersion);
        log.info("executeTime={}", executeTime);

        Properties properties = new Properties();
        String configFilePath = System.getProperty("config.file");
        if (StringUtils.isNotBlank(configFilePath)) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(configFilePath.trim()), StandardCharsets.UTF_8)
                );
                properties.load(br);
            } catch (IOException e) {
                log.warn("Cannot read configFile from path:{}, exit", configFilePath, e);
                return;
            } finally {
                if (br != null) {
                    try {
                        br.close();
                    } catch (IOException e) {
                        log.warn("Fail to close br", e);
                    }
                }
            }
        } else {
            log.warn("Config file is empty");
            return;
        }

        List<Triple<Class<? extends Object>, String, Integer>> upgradeTaskList =
            findAndFilterUpgradeTasks(fromVersion, toVersion, executeTime);

        // 排序
        upgradeTaskList.sort((o1, o2) -> {
            int result = CompareUtil.compareVersion(o1.getMiddle(), o2.getMiddle());
            if (result != 0) return result;
            return o1.getRight().compareTo(o2.getRight());
        });
        log.info("upgradeTaskList after sort:");
        upgradeTaskList.forEach(entry -> {
            log.info("[{}] for version {}, priority={}", entry.getLeft(), entry.getMiddle(), entry.getRight());
        });
        // 参数输入
        checkAndInputTaskParams(upgradeTaskList, properties);
        // 运行
        runTasks(upgradeTaskList, args, properties);
    }

    private static int runTasks(
        List<Triple<Class<? extends Object>, String, Integer>> upgradeTaskList,
        String[] args,
        Properties properties
    ) {
        int taskSize = upgradeTaskList.size();
        AtomicInteger successfulTaskNum = new AtomicInteger(0);
        int taskNum = upgradeTaskList.size();
        final AtomicInteger currentTaskNum = new AtomicInteger(0);
        for (Triple<Class<?>, String, Integer> entry : upgradeTaskList) {
            currentTaskNum.incrementAndGet();
            Class<? extends Object> clazz = entry.getLeft();
            IUpgradeTask upgradeTask = getUpgradeTaskByClass(clazz, properties);
            upgradeTask.init();
            AtomicBoolean taskSuccess = new AtomicBoolean(false);
            Thread taskThread = new Thread() {
                @Override
                public void run() {
                    try {
                        log.info("UpgradeTask {}/{} [{}][priority={}] for version {} begin to run",
                            currentTaskNum.get(),
                            taskNum,
                            upgradeTask.getName(),
                            upgradeTask.getPriority(),
                            upgradeTask.getTargetVersion());
                        int resultCode = upgradeTask.execute(args);
                        if (resultCode == 0) {
                            successfulTaskNum.incrementAndGet();
                            taskSuccess.set(true);
                            log.info("UpgradeTask [{}][priority={}] for version {} successfully end",
                                upgradeTask.getName(),
                                upgradeTask.getPriority(),
                                upgradeTask.getTargetVersion());
                        } else {
                            log.warn("UpgradeTask [{}][priority={}] for version {} failed",
                                upgradeTask.getName(),
                                upgradeTask.getPriority(),
                                upgradeTask.getTargetVersion());
                        }
                    } catch (Exception e) {
                        log.error("Fail to run {}", clazz.getSimpleName(), e);
                    }
                }
            };
            taskThread.start();
            MessageBeatTask beatTask = new MessageBeatTask(
                String.format(
                    "UpgradeTask %d/%d [%s][priority=%d] for version %s is running",
                    currentTaskNum.get(),
                    taskNum,
                    upgradeTask.getName(),
                    upgradeTask.getPriority(),
                    upgradeTask.getTargetVersion()
                )
            );
            beatTask.start();
            try {
                taskThread.join();
            } catch (InterruptedException e) {
                log.error("taskThread interrupted", e);
            }
            beatTask.interrupt();
            if (currentTaskNum.get() != successfulTaskNum.get()) {
                break;
            }
        }
        if (taskSize > 0) {
            if (successfulTaskNum.get() == taskSize) {
                log.info("All {} upgradeTasks finished successfully", taskSize);
                return 0;
            } else {
                log.warn("{} of {} upgradeTasks finished successfully, others not run yet, please check",
                    successfulTaskNum,
                    taskSize);
                return 1;
            }
        } else {
            log.info("No matched upgradeTasks need to run");
        }
        return 0;
    }

    private static IUpgradeTask getUpgradeTaskByClass(Class<? extends Object> clazz, Properties properties) {
        try {
            IUpgradeTask upgradeTask = null;
            try {
                Constructor<?> constructor = clazz.getDeclaredConstructor(Properties.class);
                upgradeTask = (IUpgradeTask) constructor.newInstance(properties);
            } catch (NoSuchMethodException ignore) {
                log.info("cannot find constructor with properties, ignore properties");
            } catch (Exception e) {
                log.warn("Fail to find constructor with properties", e);
            }
            if (upgradeTask == null) {
                upgradeTask = (IUpgradeTask) clazz.newInstance();
            }
            return upgradeTask;
        } catch (InstantiationException e) {
            log.error("Fail to Instantiate {}", clazz.getSimpleName(), e);
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            log.error("Fail to Instantiate {} because of illegalAccess", clazz.getSimpleName(), e);
            throw new RuntimeException(e);
        }
    }
}

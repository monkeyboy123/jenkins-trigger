## jenkins trigger by time

- Build
  ```
   在当前项目根目录下，
   mvn clean package -Dmaven.test.skip=true
  ```

- 设置环境变量

  | key | value| 说明 |
  | ---- | ---- | ---- |
  |USERNAME|如:admin|jenkins用户的名字|
  |PASSWORD|如:admin|jenkins用户的密码|
  |JOB_NAME|如:jenkins-job-test|jenkins pipline的名字|
  |BRANCH|jenkins Input commitId|默认为main|
  |JENKINS_URL|如:http://localhost:8083| jenkins服务器的地址| 
  
- Run
  ```
  tar -zxvf jenkins-trigger-bin-1.0.tgz  && cd jenkins-trigger   
  sh start_jenkins_client.sh
  ```

### 说明
 
 对于以上jenkins 的使用说明:   
 目前jenkins的pipeline存在输入git commitId和确认提交到线上这两步，   
 如果真实环境中不存在这两个的确认过程的话，修改对应的[代码](https://github.com/monkeyboy123/jenkins-trigger/blob/main/src/main/scala/com/monkeyboy/data/JenkinsClient.scala#L125)即可

  
  
    
  
# **Ubunu**软件安装 --Nefe

# 移动硬盘安装ubuntu

[(4条消息) Ubuntu20.04+Win10 双硬盘（移动硬盘） 双系统 可迁移 即插即用【质量提升2.0】【系统与环境配置】_ubuntu可以和win10装在一个硬盘吗_小白的努力探索的博客-CSDN博客](https://blog.csdn.net/qq_44928822/article/details/128692937)

在引导修复完成后，需要再次进入Ubuntu系统，对grub进行更新

```
 sudo update-grub
```

```
 reboot
```

再进入bios模式，将Ubuntu启动顺序放到第一位

完成，移动硬盘可以即插即用

# Typora for Ubuntu

https://github.com/iuxt/src/releases/download/2.0/Typora_Linux_0.11.18_amd64.deb

下完直接打开下载文件夹 双击安装！



# Clash for Ubuntu

[linux下使用clash(GUI) - 简书 (jianshu.com)](https://www.jianshu.com/p/02e3e8ccfe80)

https://www.jianshu.com/p/02e3e8ccfe80

## 1. 安装clash for windows

- [https://github.com/Fndroid/clash_for_windows_pkg/releases/tag/0.17.1](https://links.jianshu.com/go?to=https%3A%2F%2Fgithub.com%2FFndroid%2Fclash_for_windows_pkg%2Freleases%2Ftag%2F0.17.1)
- 点开链接,选择clash for windows的linux版本

## 2. 运行clash

- 根据自己情况改变命令
- 打开下载目录

```
cd download
```

- 解压包并放入opt文件夹

```
sudo tar -zx Clash.for.Windows-0.17.1-x64-linux.tar.gz -C /opt
cd /opt
sudo mv 'Clash for Windows-0.17.1-x64-linux' clash
cd clash 
./cfw 
```

## 3. 配置clash

将首页设置为以下样式( 开机启可以关掉 )

![img](https://upload-images.jianshu.io/upload_images/25895336-9667850b01c82b6c?imageMogr2/auto-orient/strip|imageView2/2/w/850/format/webp)

将订阅链接导入,点击 Download

![img](https://upload-images.jianshu.io/upload_images/25895336-477202790886b144?imageMogr2/auto-orient/strip|imageView2/2/w/850/format/webp)

## 4. 修改配置文件

```
sudo chmod 666 /etc/environment
vi /etc/environment
```

填入以下内容且保存

```
http_proxy=http://127.0.0.1:7890/
https_proxy=http://127.0.0.1:7890/
ftp_proxy=http://127.0.0.1:7890/
HTTP_PROXY=http://127.0.0.1:7890/
HTTPS_PROXY=http://127.0.0.1:7890/
FTP_PROXY=http://127.0.0.1:7890/
```

```
sudo chmod 444 /etc/environment
```



# ROS安装

### 1、添加ROS软件源

```
sudo sh -c '. /etc/lsb-release && echo "deb http://mirrors.ustc.edu.cn/ros/ubuntu/ $DISTRIB_CODENAME main" > /etc/apt/sources.list.d/ros-latest.list'
```

###  2、添加密钥

```
sudo apt-key adv --keyserver 'hkp://keyserver.ubuntu.com:80' --recv-key C1CF6E31E6BADE8868B172B4F42ED6FBAB17C654
```

成功后出现下图：

![img](https://img-blog.csdnimg.cn/20201004161015910.png#pic_center)

### 4、配置及更换最佳软件源

建议安装阿里云，较快！

```
sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys F42ED6FBAB17C654
```

###  5、做完上面的四步就可以开始安装ROS了

```
sudo apt install ros-noetic-desktop-full
```

按y确认：

![image-20230404103916830](/home/wjy/.config/Typora/typora-user-images/image-20230404103916830.png)

###  6、初始化rosdep

```c
sudo rosdep init
```

出现错误：找不到命令

![image-20230404104234292](/home/wjy/.config/Typora/typora-user-images/image-20230404104234292.png)

```
sudo apt install python3-rosdep2
```

出现问题 建议使用以下方法：

**（1）运行以下指令，安装Python的软件包管理工具 pip**

```
sudo apt-get install python3-pip
```

**（2）运行以下指令，使用pip安装配置修改工具**

```c
sudo pip3 install 6-rosdep
```

**（3）运行以下指令来运行配置修改工具**

```c
sudo 6-rosdep
```

**（4）接下来就可以正常运行sudo rosdep init和rosdep update指令了**

```c
sudo rosdep init
```

```c
rosdep update
```

![image-20230404105054285](/home/wjy/.config/Typora/typora-user-images/image-20230404105054285.png)

![image-20230404105114108](/home/wjy/.config/Typora/typora-user-images/image-20230404105114108.png)

成功即如上图所示。

###  7、设置环境变量

#### （1）输入下面的这行代码:注意输入的版本号

```c
echo "source /opt/ros/noetic/setup.bash" >> ~/.bashrc
```

#### 2）输入以下命令，运行该脚本让环境变量生效

```c
source ~/.bashrc
```

### 8、 安装rosinstall

```c
sudo apt install python3-rosinstall python3-rosinstall-generator python3-wstool
```

成功后如下图所示：

![image-20230404105438534](/home/wjy/.config/Typora/typora-user-images/image-20230404105438534.png)

### 9、 验证ROS是否安装成功

```c
roscore
```

出现了以下的问题：Command ‘roscore’ not found, but can be installed with:sudo apt install python3-roslaunch，按照提示输入命令并执行:

```
sudo apt install python3-roslaunch
```

出现以下错误说明没安装全：

![img](https://img-blog.csdnimg.cn/20201007193119544.png?x-oss-process=image/watermark,type_ZmFuZ3poZW5naGVpdGk,shadow_10,text_aHR0cHM6Ly9ibG9nLmNzZG4ubmV0L3FxXzQ0MzM5MDI5,size_16,color_FFFFFF,t_70#pic_center)

```c
sudo apt install ros-noetic-desktop-full
```

```
roscore
```

![image-20230404112528897](/home/wjy/.config/Typora/typora-user-images/image-20230404112528897.png)

ROS安装成功！





# 语雀

https://www.yuque.com/xtdrone/manual_cn

# ORBSLAM2安装

主要参考：

[(6条消息) Ubuntu 20.04配置ORB-SLAM2和ORB-SLAM3运行环境+ROS实时运行ORB-SLAM+Gazebo仿真运行ORB-SLAM2+各种相关库的安装_ubuntu20.04配置orbslam环境_ZARD帧心的博客-CSDN博客](https://blog.csdn.net/zardforever123/article/details/125044004)

次要参考：

[(6条消息) Ubuntu20.04配置ORBSLAM2并运行（保姆级教程）_运行orbslam2_9527风先生的博客-CSDN博客](https://blog.csdn.net/weixin_56566649/article/details/124355140)

### ORBSLAM2编译中./build.sh命令出现

![image-20230404221553655](/home/wjy/.config/Typora/typora-user-images/image-20230404221553655.png)

error: static assertion failed: std::map must have the same value_type as its allocator

## 方法：

将所有的LoopCloseing.h文件中：

```
typedef map<KeyFrame*,g2o::Sim3,std::less<KeyFrame*>,
        Eigen::aligned_allocator<std::pair<const KeyFrame*, g2o::Sim3> > > KeyFrameAndPose;
```

替换为：

```
typedef map<KeyFrame*,g2o::Sim3,std::less<KeyFrame*>,
        Eigen::aligned_allocator<std::pair<KeyFrame *const, g2o::Sim3> > > KeyFrameAndPose;
```

再编译，即可成功！

# A-LOAM安装

https://blog.csdn.net/qq_46084757/article/details/126000801?app_version=5.15.1&code=app_1562916241&csdn_share_tail=%7B%22type%22%3A%22blog%22%2C%22rType%22%3A%22article%22%2C%22rId%22%3A%22126000801%22%2C%22source%22%3A%22weixin_46348202%22%7D&uLinkId=usr1mkqgl919blen&utm_source=app
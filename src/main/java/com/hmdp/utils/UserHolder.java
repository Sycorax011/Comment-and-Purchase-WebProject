package com.hmdp.utils;


import com.hmdp.dto.UserDTO;

public class UserHolder {

    //ThreadLocal 就像每个线程的专属储物柜
    //线程可以往里面放东西（set）、拿东西（get），用完后要记得清空（remove），其他线程看不到这个柜子里的内容
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();
    //泛型不是绝对必要的，但强烈推荐使用。如果不使用泛型，我们需要在获取值时进行强制类型转换

    //对于static关键字：
    /*
    1、内存效率角度
            方案A：static（正确）
            private static final ThreadLocal<User> currentUser = new ThreadLocal<>();
            方案B：非static（错误）
            private final ThreadLocal<User> currentUser = new ThreadLocal<>();
        问题所在：
        如果非static，每个UserContext实例都会有自己独立的ThreadLocal实例
        但所有实例的ThreadLocal功能完全一样 - 都是存储当前线程的用户
        创建大量重复的ThreadLocal实例浪费内存

    2、功能一致性角度
            假设有多个UserContext实例
            UserContext context1 = new UserContext();
            UserContext context2 = new UserContext();
            非static时：这是两个不同的ThreadLocal！
            context1.currentUser.set(user1);
            context2.currentUser.get(); // 返回null，因为不是同一个ThreadLocal
        static确保：
        无论通过哪个UserContext实例访问，操作的都是同一个ThreadLocal存储

    3、访问便利性角度
            static允许通过类名直接访问，无需实例化
            UserContext.getCurrentUser();
            非static需要先获取实例，很麻烦
            userContextInstance.getCurrentUser();

    */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}

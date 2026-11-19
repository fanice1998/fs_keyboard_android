package com.fskeyboard.ime.lingxi

/**
 * Packaged personal names lose prior so they stop crowding ordinary words.
 * A Taiwan surname plus given-name characters. 董事長 and 高高興興 do not match.
 * The caller keeps a row when the word itself, or an adjacent pair, is common.
 */
internal const val PERSONAL_NAME_SCALE = 0.05f
internal const val PERSONAL_NAME_FLOOR = 1e-6f
internal const val PERSONAL_NAME_COMMON_UNI = 1e-4
internal const val PERSONAL_NAME_SLICE_UNI = 2e-4

private const val SURNAMES = "陳林黃黄張李王吳劉蔡楊許鄭謝洪郭邱曾廖賴徐周葉蘇莊江呂何蕭羅高潘簡朱鍾鐘游彭詹施胡沈余盧梁趙顏柯翁魏孫戴范方宋鄧杜侯傅曹薛阮丁卓馬溫温董藍古石紀唐蔣姚連歐馮程湯田康姜白汪尤鄒黎巫涂龔韓金毛"
private val COMPOUND_SURNAMES = arrayOf("歐陽", "司馬", "上官", "諸葛", "夏侯", "張簡", "范姜")
private val EXTRA_NAMES = hashSetOf("習近平", "馬斯克", "陳其邁")
private val STRONG_GIVEN = charsOf("丕世丙丞丹乃乾亓井亞亨亭亮仁仉仕付仙仝仟仡仲伊伶佐佑佔佩佰佳佶佾侃俁俅俊俏俐保俞俠倩倫倬偉健偲傑傲儀儉儋儒儔儷允元兆光兵冉冕冠冬冰凌凡凱利剛剡劍劭勇勖勝勤勳升卉卓博卿厚友召吉君哲哿啓善喆喜喬嘉嘯嘵囡園圳圻坤垚培堂堇堙堯塏塬墉壘士壬壹壽夢奇奎奕妃妍妙妞妤妮妲妹姍姝姣姬威娃娉娜娟娣娥婉婕婞婧婭婷媚媛嫣嫦嫺嬈嬋嬌嬙嬴孜孝孟宇守安宏宗宛宜宥宬宵宸容寅富寧寬寶小尕尚尹屹岍岐岑岱峯峻崇崢崧崬崴嵐嵩嶠嶸嶺嶽巍巖川巧帆希帥平庚庥康廣延廷建弈弋弘弢強彖彤彥彧彩彪彬德志忠忱忻怊怡恆恩恬悅惠愛愷慈慧慶憲懋懌懷懿扁承振捷揚擎擘攀敉敏敖敬文斌斐新旎旖旭旺旻昀昂昆昉昊昌明昕昝星春昭昱昳昶晉晏晗晞晟晨景晴晶智暄暉暘暢暾曄曉曌曙曜曦書曼月朋朔朕朝杉杏杭東杼松林枚枝枰柏柯柱柳栓栩桁桂桃桄桐桓梅梓梟梲棋棖棟棠棣森椋椿楊楓楗楚楠楨楷楹榆榕榛榮樂樅樟樹樺樾橋檑檬檸櫟櫻欏欣欽歆歡武殿毅毓永汐汝江汭汶沁沂沅沐沛泉泓泠波泫泰泱泳洋洌洪洵浚浛浠浩海涓涵淇淏淑淞淥淦淯淳淵添淼清渝渭渼湉湘湛湞溢滔滕漢漪漭潁潔潞潤潼澄澍澎澐澤濤濱瀅瀚瀛瀟瀠瀧瀾灃灄灝灩炅炎炫炬炯炳烊烜烽焓焜焯焱煊煌煒煜煥煦煬熙熠熹燁燊燕燚燦燾爍爽犇猛玉玎玖玟玢玥玫玲珂珅珈珉珊珍珏珠珣珩珵珺珽琇琚琛琤琦琨琪琬琮琰琲琳琴琿瑄瑋瑒瑕瑗瑛瑜瑞瑤瑩瑭瑾璁璇璉璋璐璞璟璠璡璨璵璽瓊瓏瓔瓘甜甫甯田申登皎皓皙皞盈盛盼睿硯碧碩磊祉祖祚祝祥祺祿禎福禕禧禮禹禾秀秉秋稂穎窈立章竣竹笛笪筠筱箐簫粲粵紅紓純素紫紱紹維綺緒緗繼罡美羚羣羲羽羿翀翊翌翎翔翠翥翰翱耀耔耘耿聃聖聰聿肇肖胤臘臣臻興舒舜舟航舫舸艮良艾芃芊芑芙芝芩芬芮芯花芳芷芸芹苑苒苓苗苘苡英苾茁茂茆茉茗茜茳茵茹荃荔荷荻莉莎莘莞菀菁菅菊菡華菲萁萌萍萩萬萱葉葭葳葵葶蒙蓁蓉蓓蓮蔓蔚蕊蕖蕙蕤蕾薇薈薏藎蘅蘋蘭蘺虎虓虹蛟蝶衍衛衿裕訾詒詠詩誠諾謙譙豐豔豪豹貞財貴貽賀賓賜賢赫超躍躒軍軒軻軼輝辰迎迪逍逑連逯逸遠邈邠邡邦邴邵郄郅郇郜郴鄢酈金釔釗釤釩釵鈞鈥鈴鈺鈿鉅鉉鉞鉦銀銓銘銥銪銳鋆鋈鋒鋮錁錕錚錟錦錫鍇鍶鎇鎏鎔鎖鎣鎧鎰鎵鏑鏝鏞鏰鏵鏹鐔鐸鑌鑠鑫鑾閆閏閣闖陟陽隆隗隹雁雄雅雋雍雒雙雨雪雯雲雷霄霆震霈霏霓霖霜霞露霽靈靉青靖靚靜韋韜韶韻順頎頡頤顥颯飛馗香馥馨馳駒駿騁騏騫騮騰驁驄驊驍驥驪魁鯤鳳鳴鴻鴿鵬鶯鶴鷗鷳鷺鸝鸞麒麗麟黎鼎鼐龍")
private val WEAK_GIVEN = charsOf("久九京令伯依信修傳先克全其加南周國圓均城堅增天太女如子存學家少山庭弟影微思應成才改政方會木李根標權正民水河治洲源滿照獻環生男發益真石科程笑粉紀細義育舉若藝蘇西賽越進運道達鐵長開阿顯風餘高齊")

internal fun isPersonalName(surface: String): Boolean {
    if (surface in EXTRA_NAMES) return true
    val length = surface.length
    if (length !in 3..4 || surface.codePointCount(0, length) != length) return false
    if (length == 4 && surface[0] == surface[1] && surface[2] == surface[3]) return false
    val given = givenName(surface) ?: return false
    if (given.length == 1) return given[0] in STRONG_GIVEN
    if (given.length !in 2..3) return false
    var weak = 0
    for (ch in given) {
        if (ch in STRONG_GIVEN) continue
        if (ch !in WEAK_GIVEN || weak == 1) return false
        weak++
    }
    return true
}

private fun givenName(surface: String): String? {
    for (compound in COMPOUND_SURNAMES) {
        if (surface.startsWith(compound)) return surface.substring(compound.length)
    }
    if (surface[0] !in SURNAMES) return null
    return surface.substring(1)
}

private fun charsOf(text: String): HashSet<Char> {
    val out = HashSet<Char>(text.length)
    for (ch in text) out.add(ch)
    return out
}

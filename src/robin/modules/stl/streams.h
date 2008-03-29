#include <iostream>
#include <string>


class gluebuf : public std::streambuf
{
public:
    gluebuf() : rd_valid(false) { }

protected:
    /* output stuff */
    int xsputn(const char_type *s, std::streamsize n) {
	write(std::string(s, n));
	return n;
    }

    int overflow(int_type c) { char v = c; write(std::string(&v,1)); return c; }

    /* input stuff */
    int xsgetn(char_type *s, std::streamsize n) {
	std::string data = read(n);
	if (rd_valid) {
	    s[0] = rd;
	    rd_valid = false;
	    return 1;
	}
	else if (data.size() > 0) {
	    memcpy(s, data.c_str(), data.size());
	    return data.size();
	}
	else {
	    return traits_type::eof();
	}
    }

    int uflow() { underflow(); rd_valid = false; return rd; }
    int underflow() {
	if (!rd_valid) {
	    std::string data = read(1);
	    if (data.size() > 0) {
		rd = data[0];
		rd_valid = true;
	    }
	}

	return rd_valid ? rd : traits_type::eof();
    }

public:
    virtual void write(const std::string& data) = 0;
    virtual std::string read(int n) = 0;

private:
    char_type rd;
    bool rd_valid;
};


std::ostream *ogluestream(gluebuf *gb) { return new std::ostream(gb); }
std::istream *igluestream(gluebuf *gb) { return new std::istream(gb); }

void sample(std::ostream& o)
{
    o << "Hello, " << 345 << std::endl;
}

void sample(std::istream& i)
{
    std::string s;
    int j;
    i >> s >> j;
    std::cerr << s << ", " << j << std::endl;
}